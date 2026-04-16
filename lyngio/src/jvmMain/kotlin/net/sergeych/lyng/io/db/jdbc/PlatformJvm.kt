/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng.io.db.jdbc

import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.io.db.SqlColumnMeta
import net.sergeych.lyng.io.db.SqlCoreModule
import net.sergeych.lyng.io.db.SqlDatabaseBackend
import net.sergeych.lyng.io.db.SqlExecutionResultData
import net.sergeych.lyng.io.db.SqlResultSetData
import net.sergeych.lyng.io.db.SqlTransactionBackend
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjDate
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.requireScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLNonTransientConnectionException
import java.sql.Statement
import java.util.Properties
import kotlin.time.Instant

private val knownJdbcDrivers = listOf(
    "org.sqlite.JDBC",
    "org.h2.Driver",
    "org.postgresql.Driver",
)

internal actual suspend fun openJdbcBackend(
    scope: ScopeFacade,
    core: SqlCoreModule,
    options: JdbcOpenOptions,
): SqlDatabaseBackend {
    return JdbcDatabaseBackend(core, options)
}

private class JdbcDatabaseBackend(
    private val core: SqlCoreModule,
    private val options: JdbcOpenOptions,
) : SqlDatabaseBackend {
    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqlTransactionBackend) -> T): T {
        val connection = openConnection(scope)
        try {
            connection.autoCommit = false
            val tx = JdbcTransactionBackend(core, connection)
            val result = try {
                block(tx)
            } catch (e: Throwable) {
                throw finishFailedTransaction(scope, core, e) {
                    rollbackOrThrow(scope, core, connection)
                }
            }
            try {
                connection.commit()
            } catch (e: SQLException) {
                throw mapSqlException(scope, core, e)
            }
            return result
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        } finally {
            try {
                connection.close()
            } catch (_: SQLException) {
            }
        }
    }

    private fun openConnection(scope: ScopeFacade): Connection {
        ensureJdbcDriversLoaded(scope, core, options.driverClass)
        val properties = Properties()
        options.user?.let { properties.setProperty("user", it) }
        options.password?.let { properties.setProperty("password", it) }
        options.properties.forEach { (key, value) -> properties.setProperty(key, value) }
        return try {
            DriverManager.getConnection(options.connectionUrl, properties)
        } catch (e: SQLException) {
            throw mapOpenException(scope, core, e)
        }
    }
}

private class JdbcTransactionBackend(
    private val core: SqlCoreModule,
    private val connection: Connection,
) : SqlTransactionBackend {
    override suspend fun select(scope: ScopeFacade, clause: String, params: List<Obj>): SqlResultSetData {
        try {
            connection.prepareStatement(clause).use { statement ->
                bindParams(statement, params, scope, core)
                statement.executeQuery().use { rs ->
                    return readResultSet(scope, core, rs)
                }
            }
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        }
    }

    override suspend fun execute(scope: ScopeFacade, clause: String, params: List<Obj>): SqlExecutionResultData {
        if (containsRowReturningClause(clause)) {
            scope.raiseError(
                ObjException(
                    core.sqlUsageException,
                    scope.requireScope(),
                    ObjString("execute(...) cannot be used with statements that return rows; use select(...)")
                )
            )
        }
        try {
            connection.prepareStatement(clause, Statement.RETURN_GENERATED_KEYS).use { statement ->
                bindParams(statement, params, scope, core)
                val hasResultSet = statement.execute()
                if (hasResultSet) {
                    scope.raiseError(
                        ObjException(
                            core.sqlUsageException,
                            scope.requireScope(),
                            ObjString("execute(...) cannot be used with statements that return rows; use select(...)")
                        )
                    )
                }
                val generatedKeys = statement.generatedKeys.use { rs ->
                    if (rs == null) emptyResultSet() else readResultSet(scope, core, rs)
                }
                return SqlExecutionResultData(statement.updateCount, generatedKeys)
            }
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        }
    }

    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqlTransactionBackend) -> T): T {
        val savepoint = try {
            connection.setSavepoint()
        } catch (e: SQLException) {
            throw mapSqlUsage(scope, core, "Nested transactions are not supported by this JDBC backend", e)
        }
        val nested = JdbcTransactionBackend(core, connection)
        val result = try {
            block(nested)
        } catch (e: Throwable) {
            throw finishFailedTransaction(scope, core, e) {
                rollbackToSavepointOrThrow(scope, core, connection, savepoint)
                releaseSavepointOrThrow(scope, core, connection, savepoint)
            }
        }
        try {
            connection.releaseSavepoint(savepoint)
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        }
        return result
    }
}

private suspend fun bindParams(statement: PreparedStatement, params: List<Obj>, scope: ScopeFacade, core: SqlCoreModule) {
    params.forEachIndexed { index, value ->
        val jdbcIndex = index + 1
        when (value) {
            ObjNull -> statement.setObject(jdbcIndex, null)
            is ObjBool -> statement.setBoolean(jdbcIndex, value.value)
            is ObjInt -> statement.setLong(jdbcIndex, value.value)
            is ObjReal -> statement.setDouble(jdbcIndex, value.value)
            is ObjString -> statement.setString(jdbcIndex, value.value)
            is ObjBuffer -> statement.setBytes(jdbcIndex, value.byteArray.toByteArray())
            is ObjDate -> statement.setObject(jdbcIndex, java.time.LocalDate.parse(value.date.toString()))
            is ObjDateTime -> statement.setObject(jdbcIndex, java.time.LocalDateTime.parse(value.localDateTime.toString()))
            is ObjInstant -> statement.setObject(jdbcIndex, java.time.Instant.parse(value.instant.toString()))
            else -> when (value.objClass.className) {
                "Decimal" -> statement.setBigDecimal(jdbcIndex, BigDecimal(scope.toStringOf(value).value))
                else -> scope.raiseError(
                    ObjException(
                        core.sqlUsageException,
                        scope.requireScope(),
                        ObjString("Unsupported JDBC parameter type: ${value.objClass.className}")
                    )
                )
            }
        }
    }
}

private suspend fun readResultSet(scope: ScopeFacade, core: SqlCoreModule, resultSet: ResultSet): SqlResultSetData {
    val meta = resultSet.metaData
    val columns = (1..meta.columnCount).map { index ->
        val nativeType = meta.getColumnTypeName(index) ?: ""
        SqlColumnMeta(
            name = meta.getColumnLabel(index),
            sqlType = mapSqlType(core, nativeType, meta.getColumnType(index)),
            nullable = meta.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls,
            nativeType = nativeType,
        )
    }
    val rows = mutableListOf<List<Obj>>()
    while (resultSet.next()) {
        rows += columns.mapIndexed { index, column ->
            readColumnValue(scope, core, resultSet, index + 1, column.nativeType)
        }
    }
    return SqlResultSetData(columns, rows)
}

private suspend fun readColumnValue(
    scope: ScopeFacade,
    core: SqlCoreModule,
    resultSet: ResultSet,
    index: Int,
    nativeType: String,
): Obj {
    val value = resultSet.getObject(index) ?: return ObjNull
    val normalizedNativeType = normalizeDeclaredTypeName(nativeType)
    return when (value) {
        is Boolean -> ObjBool(value)
        is Byte, is Short, is Int -> ObjInt.of((value as Number).toLong())
        is Long -> ObjInt.of(value)
        is Float, is Double -> ObjReal.of((value as Number).toDouble())
        is java.math.BigInteger -> ObjInt.of(value.longValueExact())
        is BigDecimal -> decimalFromString(scope, value.toPlainString())
        is ByteArray -> ObjBuffer(value.toUByteArray())
        is java.sql.Date -> ObjDate(LocalDate.parse(value.toLocalDate().toString()))
        is java.sql.Timestamp -> timestampValue(scope, normalizedNativeType, value.toInstant())
        is java.time.LocalDate -> ObjDate(LocalDate.parse(value.toString()))
        is java.time.LocalDateTime -> ObjDateTime(value.toString().let(LocalDateTime::parse).toInstant(TimeZone.UTC), TimeZone.UTC)
        is java.time.OffsetDateTime -> ObjInstant(Instant.parse(value.toInstant().toString()))
        is java.time.ZonedDateTime -> ObjInstant(Instant.parse(value.toInstant().toString()))
        is java.time.Instant -> ObjInstant(Instant.parse(value.toString()))
        is java.sql.Time -> ObjString(value.toLocalTime().toString())
        is java.time.LocalTime -> ObjString(value.toString())
        is java.time.OffsetTime -> ObjString(value.toString())
        is String -> stringValue(scope, normalizedNativeType, value)
        else -> ObjString(value.toString())
    }
}

private suspend fun stringValue(scope: ScopeFacade, normalizedNativeType: String, value: String): Obj {
    return when {
        normalizedNativeType == "DATE" -> ObjDate(LocalDate.parse(value.trim()))
        normalizedNativeType == "TIMESTAMP WITH TIME ZONE" || normalizedNativeType == "TIMESTAMPTZ" ->
            ObjInstant(Instant.parse(value.trim()))
        normalizedNativeType == "TIMESTAMP" || normalizedNativeType == "DATETIME" ->
            ObjDateTime(LocalDateTime.parse(value.trim()).toInstant(TimeZone.UTC), TimeZone.UTC)
        normalizedNativeType == "DECIMAL" || normalizedNativeType == "NUMERIC" -> decimalFromString(scope, value.trim())
        else -> ObjString(value)
    }
}

private fun timestampValue(scope: ScopeFacade, normalizedNativeType: String, value: java.time.Instant): Obj {
    return if (normalizedNativeType == "TIMESTAMP WITH TIME ZONE" || normalizedNativeType == "TIMESTAMPTZ") {
        ObjInstant(Instant.parse(value.toString()))
    } else {
        val local = value.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime().toString()
        ObjDateTime(LocalDateTime.parse(local).toInstant(TimeZone.UTC), TimeZone.UTC)
    }
}

private suspend fun decimalFromString(scope: ScopeFacade, value: String): Obj {
    val decimalModule = scope.requireScope().currentImportProvider.createModuleScope(scope.pos, "lyng.decimal")
    val decimalClass = decimalModule.requireClass("Decimal")
    return decimalClass.invokeInstanceMethod(scope.requireScope(), "fromString", ObjString(value))
}

private fun normalizeDeclaredTypeName(nativeTypeName: String): String {
    val strippedSuffix = nativeTypeName.trim().replace(Regex("""\s*\(.*\)\s*$"""), "")
    return strippedSuffix.uppercase().replace(Regex("""\s+"""), " ").trim()
}

private fun mapSqlType(core: SqlCoreModule, nativeTypeName: String, jdbcType: Int): ObjEnumEntry {
    val normalized = normalizeDeclaredTypeName(nativeTypeName)
    return when {
        normalized == "BOOLEAN" || normalized == "BOOL" -> core.sqlTypes.require("Bool")
        normalized == "DATE" -> core.sqlTypes.require("Date")
        normalized == "TIMESTAMP" || normalized == "DATETIME" -> core.sqlTypes.require("DateTime")
        normalized == "TIMESTAMP WITH TIME ZONE" || normalized == "TIMESTAMPTZ" -> core.sqlTypes.require("Instant")
        normalized == "TIME" || normalized == "TIME WITHOUT TIME ZONE" || normalized == "TIME WITH TIME ZONE" -> core.sqlTypes.require("String")
        normalized == "DECIMAL" || normalized == "NUMERIC" -> core.sqlTypes.require("Decimal")
        normalized.contains("BLOB") || normalized.contains("BINARY") || normalized == "BYTEA" -> core.sqlTypes.require("Binary")
        normalized.contains("INT") -> core.sqlTypes.require("Int")
        normalized.contains("CHAR") || normalized.contains("TEXT") || normalized.contains("CLOB") || normalized == "VARCHAR" -> core.sqlTypes.require("String")
        normalized.contains("REAL") || normalized.contains("FLOA") || normalized.contains("DOUB") -> core.sqlTypes.require("Double")
        jdbcType == java.sql.Types.BOOLEAN || jdbcType == java.sql.Types.BIT -> core.sqlTypes.require("Bool")
        jdbcType == java.sql.Types.DATE -> core.sqlTypes.require("Date")
        jdbcType == java.sql.Types.TIMESTAMP -> core.sqlTypes.require("DateTime")
        jdbcType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> core.sqlTypes.require("Instant")
        jdbcType == java.sql.Types.TIME || jdbcType == java.sql.Types.TIME_WITH_TIMEZONE -> core.sqlTypes.require("String")
        jdbcType == java.sql.Types.BLOB || jdbcType == java.sql.Types.BINARY || jdbcType == java.sql.Types.VARBINARY || jdbcType == java.sql.Types.LONGVARBINARY -> core.sqlTypes.require("Binary")
        jdbcType == java.sql.Types.INTEGER || jdbcType == java.sql.Types.BIGINT || jdbcType == java.sql.Types.SMALLINT || jdbcType == java.sql.Types.TINYINT -> core.sqlTypes.require("Int")
        jdbcType == java.sql.Types.DECIMAL || jdbcType == java.sql.Types.NUMERIC -> core.sqlTypes.require("Decimal")
        jdbcType == java.sql.Types.FLOAT || jdbcType == java.sql.Types.REAL || jdbcType == java.sql.Types.DOUBLE -> core.sqlTypes.require("Double")
        else -> core.sqlTypes.require("String")
    }
}

private fun emptyResultSet(): SqlResultSetData = SqlResultSetData(emptyList(), emptyList())

private fun containsRowReturningClause(clause: String): Boolean =
    Regex("""\b(returning|output)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clause)

private fun ensureJdbcDriversLoaded(scope: ScopeFacade, core: SqlCoreModule, requestedDriverClass: String?) {
    for (driverClass in knownJdbcDrivers) {
        try {
            Class.forName(driverClass)
        } catch (_: Throwable) {
        }
    }
    val explicit = requestedDriverClass ?: return
    try {
        Class.forName(explicit)
    } catch (e: ClassNotFoundException) {
        throw ExecutionError(
            ObjException(core.databaseException, scope.requireScope(), ObjString("JDBC driver class not found: $explicit")),
            scope.pos,
            "JDBC driver class not found: $explicit",
            e,
        )
    }
}

private fun rollbackOrThrow(scope: ScopeFacade, core: SqlCoreModule, connection: Connection) {
    try {
        connection.rollback()
    } catch (e: SQLException) {
        throw mapSqlException(scope, core, e)
    }
}

private fun rollbackToSavepointOrThrow(
    scope: ScopeFacade,
    core: SqlCoreModule,
    connection: Connection,
    savepoint: java.sql.Savepoint,
) {
    try {
        connection.rollback(savepoint)
    } catch (e: SQLException) {
        throw mapSqlException(scope, core, e)
    }
}

private fun releaseSavepointOrThrow(
    scope: ScopeFacade,
    core: SqlCoreModule,
    connection: Connection,
    savepoint: java.sql.Savepoint,
) {
    try {
        connection.releaseSavepoint(savepoint)
    } catch (e: SQLException) {
        throw mapSqlException(scope, core, e)
    }
}

private inline fun finishFailedTransaction(
    scope: ScopeFacade,
    core: SqlCoreModule,
    failure: Throwable,
    rollback: () -> Unit,
): Throwable {
    return try {
        rollback()
        failure
    } catch (rollbackFailure: Throwable) {
        if (isRollbackSignal(failure, core)) {
            attachSecondaryFailure(rollbackFailure, failure)
            rollbackFailure
        } else {
            attachSecondaryFailure(failure, rollbackFailure)
            failure
        }
    }
}

private fun isRollbackSignal(failure: Throwable, core: SqlCoreModule): Boolean {
    val errorObject = (failure as? ExecutionError)?.errorObject ?: return false
    return errorObject.isInstanceOf(core.rollbackException)
}

private fun attachSecondaryFailure(primary: Throwable, secondary: Throwable) {
    if (primary === secondary) return
    primary.addSuppressed(secondary)
}

private fun mapOpenException(scope: ScopeFacade, core: SqlCoreModule, e: SQLException): Nothing {
    val message = e.message ?: "JDBC open failed"
    if (e is SQLNonTransientConnectionException) {
        throw ExecutionError(
            ObjException(core.databaseException, scope.requireScope(), ObjString(message)),
            scope.pos,
            message,
            e,
        )
    }
    throw ExecutionError(
        ObjException(core.databaseException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
        e,
    )
}

private fun mapSqlException(scope: ScopeFacade, core: SqlCoreModule, e: SQLException): ExecutionError {
    val exceptionClass = when {
        e is SQLIntegrityConstraintViolationException -> core.sqlConstraintException
        e.sqlState?.startsWith("23") == true -> core.sqlConstraintException
        else -> core.sqlExecutionException
    }
    return ExecutionError(
        ObjException(exceptionClass, scope.requireScope(), ObjString(e.message ?: "JDBC error")),
        scope.pos,
        e.message ?: "JDBC error",
        e,
    )
}

private fun mapSqlUsage(scope: ScopeFacade, core: SqlCoreModule, message: String, cause: Throwable? = null): ExecutionError {
    return ExecutionError(
        ObjException(core.sqlUsageException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
        cause,
    )
}
