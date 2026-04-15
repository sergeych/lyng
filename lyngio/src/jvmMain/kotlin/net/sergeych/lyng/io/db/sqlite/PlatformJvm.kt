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

package net.sergeych.lyng.io.db.sqlite

import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.requireScope
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteOpenMode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import kotlin.time.Instant

internal actual suspend fun openSqliteBackend(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    options: SqliteOpenOptions,
): SqliteDatabaseBackend {
    if (options.busyTimeoutMillis < 0) {
        scope.raiseIllegalArgument("busyTimeoutMillis must be >= 0")
    }
    return JdbcSqliteDatabaseBackend(core, options)
}

private class JdbcSqliteDatabaseBackend(
    private val core: SqliteCoreModule,
    private val options: SqliteOpenOptions,
) : SqliteDatabaseBackend {
    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T {
        val connection = openConnection(scope)
        try {
            connection.autoCommit = false
            val tx = JdbcSqliteTransactionBackend(core, connection)
            return try {
                val result = block(tx)
                connection.commit()
                result
            } catch (e: Throwable) {
                rollbackQuietly(connection)
                throw e
            }
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
        try {
            val config = SQLiteConfig().apply {
                setOpenMode(SQLiteOpenMode.OPEN_URI)
                if (options.readOnly) {
                    setReadOnly(true)
                    setOpenMode(SQLiteOpenMode.READONLY)
                } else {
                    setReadOnly(false)
                    setOpenMode(SQLiteOpenMode.READWRITE)
                    if (options.createIfMissing) {
                        setOpenMode(SQLiteOpenMode.CREATE)
                    }
                }
                enforceForeignKeys(options.foreignKeys)
                busyTimeout = options.busyTimeoutMillis
            }
            return DriverManager.getConnection(jdbcUrl(options.path), config.toProperties())
        } catch (e: SQLException) {
            throw mapOpenException(scope, core, e)
        } catch (e: IllegalArgumentException) {
            scope.raiseIllegalArgument(e.message ?: "Invalid SQLite configuration")
        }
    }

    private fun jdbcUrl(path: String): String {
        return when (path) {
            ":memory:" -> "jdbc:sqlite::memory:"
            else -> if (path.startsWith("/")) "jdbc:sqlite:$path" else "jdbc:sqlite:$path"
        }
    }
}

private class JdbcSqliteTransactionBackend(
    private val core: SqliteCoreModule,
    private val connection: Connection,
) : SqliteTransactionBackend {
    override suspend fun select(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteResultSetData {
        try {
            connection.prepareStatement(clause).use { statement ->
                bindParams(statement, params, scope)
                statement.executeQuery().use { rs ->
                    return readResultSet(scope, core, rs)
                }
            }
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        }
    }

    override suspend fun execute(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteExecutionResultData {
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
                bindParams(statement, params, scope)
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
                val affected = statement.updateCount
                val generatedKeys = statement.generatedKeys.use { rs ->
                    if (rs == null) {
                        emptyResultSet(core)
                    } else {
                        readResultSet(scope, core, rs)
                    }
                }
                return SqliteExecutionResultData(affected, generatedKeys)
            }
        } catch (e: SQLException) {
            throw mapSqlException(scope, core, e)
        }
    }

    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T {
        val savepoint = try {
            connection.setSavepoint()
        } catch (e: SQLException) {
            throw mapSqlUsage(scope, core, "Nested transactions are not supported by this SQLite backend", e)
        }
        return try {
            val result = block(JdbcSqliteTransactionBackend(core, connection))
            connection.releaseSavepoint(savepoint)
            result
        } catch (e: Throwable) {
            rollbackQuietly(connection, savepoint)
            throw e
        }
    }
}

private suspend fun bindParams(statement: PreparedStatement, params: List<Obj>, scope: ScopeFacade) {
    params.forEachIndexed { index, value ->
        val jdbcIndex = index + 1
        when (value) {
            ObjNull -> statement.setObject(jdbcIndex, null)
            is ObjBool -> statement.setBoolean(jdbcIndex, value.value)
            is ObjInt -> statement.setLong(jdbcIndex, value.value)
            is ObjReal -> statement.setDouble(jdbcIndex, value.value)
            is ObjString -> statement.setString(jdbcIndex, value.value)
            is ObjBuffer -> statement.setBytes(jdbcIndex, value.byteArray.toByteArray())
            is ObjInstant -> statement.setString(jdbcIndex, value.instant.toString())
            is ObjDateTime -> statement.setString(jdbcIndex, value.localDateTime.toString())
            else -> when (value.objClass.className) {
                "Date", "Decimal" -> statement.setString(jdbcIndex, scope.toStringOf(value).value)
                else -> scope.raiseClassCastError("Unsupported SQLite parameter type: ${value.objClass.className}")
            }
        }
    }
}

private suspend fun readResultSet(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    resultSet: ResultSet,
): SqliteResultSetData {
    val meta = resultSet.metaData
    val columns = (1..meta.columnCount).map { index ->
        SqliteColumnMeta(
            name = meta.getColumnLabel(index),
            sqlType = mapSqlType(core, meta.getColumnTypeName(index), meta.getColumnType(index)),
            nullable = meta.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls,
            nativeType = meta.getColumnTypeName(index) ?: "",
        )
    }
    val rows = mutableListOf<List<Obj>>()
    while (resultSet.next()) {
        rows += columns.mapIndexed { index, column ->
            readColumnValue(scope, core, resultSet, index + 1, column.nativeType)
        }
    }
    return SqliteResultSetData(columns, rows)
}

private suspend fun readColumnValue(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    resultSet: ResultSet,
    index: Int,
    nativeType: String,
): Obj {
    val value = resultSet.getObject(index) ?: return ObjNull
    if (isDecimalNativeType(nativeType) && value is Number) {
        return decimalFromString(scope, value.toString())
    }
    return when (value) {
        is Boolean -> ObjBool(value)
        is Byte, is Short, is Int -> ObjInt.of((value as Number).toLong())
        is Long -> ObjInt.of(value)
        is Float, is Double -> ObjReal.of((value as Number).toDouble())
        is ByteArray -> ObjBuffer(value.toUByteArray())
        is String -> convertStringValue(scope, core, nativeType, value)
        is java.math.BigDecimal -> decimalFromString(scope, value.toPlainString())
        else -> ObjString(value.toString())
    }
}

private suspend fun convertStringValue(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    nativeType: String,
    value: String,
): Obj {
    val normalized = nativeType.trim().uppercase()
    return when {
        normalized == "DECIMAL" || normalized == "NUMERIC" -> decimalFromString(scope, value)
        normalized == "DATETIME" || normalized == "TIMESTAMP" -> dateTimeFromString(value)
        normalized == "TIMESTAMP WITH TIME ZONE" || normalized == "TIMESTAMPTZ" ->
            ObjInstant(Instant.parse(value))
        else -> ObjString(value)
    }
}

private fun isDecimalNativeType(nativeType: String): Boolean {
    val normalized = nativeType.trim().uppercase()
    return normalized == "DECIMAL" || normalized == "NUMERIC"
}

private suspend fun decimalFromString(scope: ScopeFacade, value: String): Obj {
    val decimalModule = scope.requireScope().currentImportProvider.createModuleScope(scope.pos, "lyng.decimal")
    val decimalClass = decimalModule.requireClass("Decimal")
    return decimalClass.invokeInstanceMethod(scope.requireScope(), "fromString", ObjString(value))
}

private fun dateTimeFromString(value: String): ObjDateTime {
    val trimmed = value.trim()
    return if (hasExplicitTimeZone(trimmed)) {
        val instant = Instant.parse(trimmed)
        ObjDateTime(instant, parseTimeZoneOrUtc(trimmed))
    } else {
        val local = LocalDateTime.parse(trimmed)
        ObjDateTime(local.toInstant(TimeZone.UTC), TimeZone.UTC)
    }
}

private fun hasExplicitTimeZone(value: String): Boolean {
    if (value.endsWith("Z", ignoreCase = true)) return true
    val tIndex = value.indexOf('T')
    if (tIndex < 0) return false
    val plus = value.lastIndexOf('+')
    val minus = value.lastIndexOf('-')
    val offsetStart = maxOf(plus, minus)
    return offsetStart > tIndex
}

private fun containsRowReturningClause(clause: String): Boolean =
    Regex("""\breturning\b""", RegexOption.IGNORE_CASE).containsMatchIn(clause)

private fun parseTimeZoneOrUtc(value: String): TimeZone {
    if (value.endsWith("Z", ignoreCase = true)) return TimeZone.UTC
    val tIndex = value.indexOf('T')
    if (tIndex < 0) return TimeZone.UTC
    val plus = value.lastIndexOf('+')
    val minus = value.lastIndexOf('-')
    val offsetStart = maxOf(plus, minus)
    if (offsetStart <= tIndex) return TimeZone.UTC
    return try {
        TimeZone.of(value.substring(offsetStart))
    } catch (_: IllegalArgumentException) {
        TimeZone.UTC
    }
}

private fun mapSqlType(core: SqliteCoreModule, nativeTypeName: String, jdbcType: Int): ObjEnumEntry {
    val normalized = nativeTypeName.trim().uppercase()
    return when {
        normalized == "BOOLEAN" -> core.sqlTypes.require("Bool")
        normalized == "DATE" -> core.sqlTypes.require("Date")
        normalized == "DATETIME" || normalized == "TIMESTAMP" -> core.sqlTypes.require("DateTime")
        normalized == "TIMESTAMP WITH TIME ZONE" || normalized == "TIMESTAMPTZ" -> core.sqlTypes.require("Instant")
        normalized == "DECIMAL" || normalized == "NUMERIC" -> core.sqlTypes.require("Decimal")
        normalized.contains("BLOB") -> core.sqlTypes.require("Binary")
        normalized.contains("INT") -> core.sqlTypes.require("Int")
        normalized.contains("CHAR") || normalized.contains("TEXT") || normalized.contains("CLOB") -> core.sqlTypes.require("String")
        normalized.contains("REAL") || normalized.contains("FLOA") || normalized.contains("DOUB") -> core.sqlTypes.require("Double")
        jdbcType == java.sql.Types.BOOLEAN -> core.sqlTypes.require("Bool")
        jdbcType == java.sql.Types.BLOB || jdbcType == java.sql.Types.BINARY || jdbcType == java.sql.Types.VARBINARY -> core.sqlTypes.require("Binary")
        jdbcType == java.sql.Types.INTEGER -> core.sqlTypes.require("Int")
        jdbcType == java.sql.Types.BIGINT -> core.sqlTypes.require("Int")
        jdbcType == java.sql.Types.DECIMAL || jdbcType == java.sql.Types.NUMERIC -> core.sqlTypes.require("Decimal")
        jdbcType == java.sql.Types.FLOAT || jdbcType == java.sql.Types.REAL || jdbcType == java.sql.Types.DOUBLE -> core.sqlTypes.require("Double")
        else -> core.sqlTypes.require("String")
    }
}

private fun emptyResultSet(core: SqliteCoreModule): SqliteResultSetData = SqliteResultSetData(emptyList(), emptyList())

private fun rollbackQuietly(connection: Connection, savepoint: java.sql.Savepoint? = null) {
    try {
        if (savepoint == null) connection.rollback() else connection.rollback(savepoint)
    } catch (_: SQLException) {
    }
}

private fun mapOpenException(scope: ScopeFacade, core: SqliteCoreModule, e: SQLException): Nothing {
    val message = e.message ?: "SQLite open failed"
    val lower = message.lowercase()
    if ("malformed" in lower || "no such access mode" in lower || "invalid uri" in lower) {
        scope.raiseIllegalArgument(message)
    }
    throw mapSqlException(scope, core, e)
}

private fun mapSqlException(scope: ScopeFacade, core: SqliteCoreModule, e: SQLException): ExecutionError {
    val code = SQLiteErrorCode.getErrorCode(e.errorCode)
    val exceptionClass = when (code) {
        SQLiteErrorCode.SQLITE_CONSTRAINT,
        SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY,
        SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
        SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY,
        SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL -> core.sqlConstraintException
        else -> core.sqlExecutionException
    }
    return ExecutionError(
        ObjException(exceptionClass, scope.requireScope(), ObjString(e.message ?: "SQLite error")),
        scope.pos,
        e.message ?: "SQLite error",
        e,
    )
}

private fun mapSqlUsage(scope: ScopeFacade, core: SqliteCoreModule, message: String, cause: Throwable? = null): ExecutionError {
    return ExecutionError(
        ObjException(core.sqlUsageException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
        cause,
    )
}
