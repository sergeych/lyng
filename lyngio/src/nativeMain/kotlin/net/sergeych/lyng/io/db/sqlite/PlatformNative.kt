@file:OptIn(ExperimentalForeignApi::class)

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

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_BLOB
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_CONSTRAINT
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_DONE
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_FLOAT
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_INTEGER
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_NULL
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_OK
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_OPEN_CREATE
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_OPEN_READONLY
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_OPEN_READWRITE
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_OPEN_URI
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_ROW
import net.sergeych.lyng.io.db.sqlite.cinterop.SQLITE_TEXT
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_blob
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_double
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_int64
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_null
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_parameter_count
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_bind_text
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_busy_timeout
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_changes
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_close_v2
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_blob
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_bytes
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_count
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_decltype
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_double
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_int64
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_name
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_text
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_column_type
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_errmsg
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_extended_errcode
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_extended_result_codes
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_finalize
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_last_insert_rowid
import net.sergeych.lyng.io.db.sqlite.cinterop.lyng_sqlite3_open
import net.sergeych.lyng.io.db.sqlite.cinterop.lyng_sqlite3_prepare
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_open_v2
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_reset
import net.sergeych.lyng.io.db.sqlite.cinterop.sqlite3_step
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjDate
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.requireScope
import platform.posix.memcpy
import kotlin.time.Instant

internal actual suspend fun openSqliteBackend(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    options: SqliteOpenOptions,
): SqliteDatabaseBackend {
    if (options.busyTimeoutMillis < 0) {
        scope.raiseIllegalArgument("busyTimeoutMillis must be >= 0")
    }
    return NativeSqliteDatabaseBackend(core, options)
}

private class NativeSqliteDatabaseBackend(
    private val core: SqliteCoreModule,
    private val options: SqliteOpenOptions,
) : SqliteDatabaseBackend {
    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T {
        val handle = openHandle(scope, core, options)
        val savepoints = SavepointCounter()
        try {
            handle.execUnit(scope, core, "begin")
            val tx = NativeSqliteTransactionBackend(core, handle, savepoints)
            return try {
                val result = block(tx)
                handle.execUnit(scope, core, "commit")
                result
            } catch (e: Throwable) {
                handle.execUnitQuietly("rollback")
                throw e
            }
        } finally {
            handle.close()
        }
    }
}

private class NativeSqliteTransactionBackend(
    private val core: SqliteCoreModule,
    private val handle: NativeSqliteHandle,
    private val savepoints: SavepointCounter,
) : SqliteTransactionBackend {
    override suspend fun select(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteResultSetData {
        return handle.select(scope, core, clause, params)
    }

    override suspend fun execute(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteExecutionResultData {
        return handle.execute(scope, core, clause, params)
    }

    override suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T {
        val savepoint = "lyng_sp_${savepoints.next()}"
        handle.execUnit(scope, core, "savepoint $savepoint")
        return try {
            val result = block(NativeSqliteTransactionBackend(core, handle, savepoints))
            handle.execUnit(scope, core, "release savepoint $savepoint")
            result
        } catch (e: Throwable) {
            handle.execUnitQuietly("rollback to savepoint $savepoint")
            handle.execUnitQuietly("release savepoint $savepoint")
            throw e
        }
    }
}

private class SavepointCounter {
    private var nextValue = 0

    fun next(): Int {
        nextValue += 1
        return nextValue
    }
}

private class NativeSqliteHandle(
    private val db: CPointer<sqlite3>,
) {
    suspend fun select(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        clause: String,
        params: List<Obj>,
    ): SqliteResultSetData = memScoped {
        val stmt = prepare(scope, core, clause)
        try {
            bindParams(scope, core, stmt, params, this)
            readResultSet(scope, core, stmt)
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    suspend fun execute(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        clause: String,
        params: List<Obj>,
    ): SqliteExecutionResultData = memScoped {
        if (containsRowReturningClause(clause)) {
            raiseExecuteReturningUsage(scope, core)
        }
        val stmt = prepare(scope, core, clause)
        try {
            bindParams(scope, core, stmt, params, this)
            when (val rc = sqlite3_step(stmt)) {
                SQLITE_DONE -> {
                    val affectedRows = sqlite3_changes(db)
                    val generatedKeys = readGeneratedKeys(core, clause, affectedRows)
                    SqliteExecutionResultData(affectedRows, generatedKeys)
                }
                SQLITE_ROW -> raiseExecuteReturningUsage(scope, core)
                else -> throw sqlError(scope, core, rc)
            }
        } finally {
            sqlite3_reset(stmt)
            sqlite3_finalize(stmt)
        }
    }

    fun execUnit(scope: ScopeFacade, core: SqliteCoreModule, sql: String) {
        memScoped {
            val stmt = prepare(scope, core, sql)
            try {
                when (val rc = sqlite3_step(stmt)) {
                    SQLITE_DONE, SQLITE_ROW -> Unit
                    else -> throw sqlError(scope, core, rc)
                }
            } finally {
                sqlite3_finalize(stmt)
            }
        }
    }

    fun execUnitQuietly(sql: String) {
        memScoped {
            val stmt = lyng_sqlite3_prepare(db, sql) ?: return@memScoped
            try {
                sqlite3_step(stmt)
            } finally {
                sqlite3_finalize(stmt)
            }
        }
    }

    fun close() {
        sqlite3_close_v2(db)
    }

    private fun MemScope.prepare(scope: ScopeFacade, core: SqliteCoreModule, sql: String): CPointer<sqlite3_stmt> {
        return lyng_sqlite3_prepare(db, sql) ?: throw sqlError(scope, core, sqlite3_extended_errcode(db))
    }

    private suspend fun bindParams(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        stmt: CPointer<sqlite3_stmt>,
        params: List<Obj>,
        memScope: MemScope,
    ) {
        val expectedCount = sqlite3_bind_parameter_count(stmt)
        if (expectedCount != params.size) {
            throw usageError(
                scope,
                core,
                "SQL parameter count mismatch: statement expects $expectedCount value(s), got ${params.size}"
            )
        }
        params.forEachIndexed { index, value ->
            val parameterIndex = index + 1
            val rc = when (value) {
                ObjNull -> sqlite3_bind_null(stmt, parameterIndex)
                is ObjBool -> sqlite3_bind_int64(stmt, parameterIndex, if (value.value) 1L else 0L)
                is ObjInt -> sqlite3_bind_int64(stmt, parameterIndex, value.value)
                is ObjReal -> sqlite3_bind_double(stmt, parameterIndex, value.value)
                is ObjString -> bindText(stmt, parameterIndex, value.value, memScope)
                is ObjBuffer -> bindBlob(stmt, parameterIndex, value.byteArray.toByteArray(), memScope)
                is ObjInstant -> bindText(stmt, parameterIndex, value.instant.toString(), memScope)
                is ObjDateTime -> bindText(stmt, parameterIndex, value.localDateTime.toString(), memScope)
                else -> when (value.objClass.className) {
                    "Date", "Decimal" -> bindText(stmt, parameterIndex, scope.toStringOf(value).value, memScope)
                    else -> throw usageError(
                        scope,
                        core,
                        "Unsupported SQLite parameter type: ${value.objClass.className}"
                    )
                }
            }
            if (rc != SQLITE_OK) {
                throw sqlError(scope, core, rc)
            }
        }
    }

    private fun bindText(
        stmt: CPointer<sqlite3_stmt>,
        parameterIndex: Int,
        value: String,
        memScope: MemScope,
    ): Int {
        return sqlite3_bind_text(stmt, parameterIndex, value, -1, SQLITE_TRANSIENT)
    }

    private fun bindBlob(
        stmt: CPointer<sqlite3_stmt>,
        parameterIndex: Int,
        value: ByteArray,
        memScope: MemScope,
    ): Int {
        if (value.isEmpty()) {
            return sqlite3_bind_blob(stmt, parameterIndex, null, 0, null)
        }
        val target = memScope.allocArray<ByteVar>(value.size)
        value.usePinned { pinned ->
            memcpy(target, pinned.addressOf(0), value.size.toULong())
        }
        return sqlite3_bind_blob(stmt, parameterIndex, target, value.size, SQLITE_TRANSIENT)
    }

    private suspend fun readResultSet(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        stmt: CPointer<sqlite3_stmt>,
    ): SqliteResultSetData {
        val columnCount = sqlite3_column_count(stmt)
        val columns = (0 until columnCount).map { index ->
            val nativeType = sqlite3_column_decltype(stmt, index)?.toKString().orEmpty()
            SqliteColumnMeta(
                name = sqlite3_column_name(stmt, index)?.toKString().orEmpty(),
                sqlType = mapSqlType(core, nativeType, SQLITE_NULL),
                nullable = true,
                nativeType = nativeType,
            )
        }.toMutableList()
        if (columnCount == 0) {
            return emptyResultSet()
        }

        val rows = mutableListOf<List<Obj>>()
        while (true) {
            when (val rc = sqlite3_step(stmt)) {
                SQLITE_ROW -> {
                    val row = (0 until columnCount).map { index ->
                        val dynamicType = sqlite3_column_type(stmt, index)
                        if (columns[index].nativeType.isBlank()) {
                            columns[index] = columns[index].copy(sqlType = mapSqlType(core, columns[index].nativeType, dynamicType))
                        }
                        readColumnValue(scope, core, stmt, index, columns[index].nativeType)
                    }
                    rows += row
                }
                SQLITE_DONE -> return SqliteResultSetData(columns, rows)
                else -> throw sqlError(scope, core, rc)
            }
        }
    }

    private suspend fun readColumnValue(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        stmt: CPointer<sqlite3_stmt>,
        index: Int,
        nativeType: String,
    ): Obj {
        val normalizedNativeType = normalizeDeclaredTypeName(nativeType)
        return when (val type = sqlite3_column_type(stmt, index)) {
            SQLITE_NULL -> ObjNull
            SQLITE_INTEGER -> {
                val value = sqlite3_column_int64(stmt, index)
                when {
                    isBooleanNativeType(normalizedNativeType) -> integerToBool(scope, core, value)
                    isDecimalNativeType(normalizedNativeType) -> decimalFromString(scope, value.toString())
                    else -> ObjInt.of(value)
                }
            }
            SQLITE_FLOAT -> {
                val value = sqlite3_column_double(stmt, index)
                if (isDecimalNativeType(normalizedNativeType)) decimalFromString(scope, value.toString()) else ObjReal.of(value)
            }
            SQLITE_TEXT -> {
                val textPtr = sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>()
                val value = textPtr?.toKString() ?: ""
                convertStringValue(scope, core, normalizedNativeType, value)
            }
            SQLITE_BLOB -> {
                val size = sqlite3_column_bytes(stmt, index)
                val blob = sqlite3_column_blob(stmt, index)
                val bytes = if (blob == null || size <= 0) byteArrayOf() else blob.reinterpret<ByteVar>().readBytes(size)
                ObjBuffer(bytes.toUByteArray())
            }
            else -> ObjString(columnText(stmt, index))
        }
    }

    private suspend fun convertStringValue(
        scope: ScopeFacade,
        core: SqliteCoreModule,
        normalizedNativeType: String,
        value: String,
    ): Obj {
        return when {
            isBooleanNativeType(normalizedNativeType) -> stringToBool(scope, core, value)
            isDecimalNativeType(normalizedNativeType) -> decimalFromString(scope, value.trim())
            normalizedNativeType == "DATE" -> ObjDate(LocalDate.parse(value.trim()))
            normalizedNativeType == "DATETIME" || normalizedNativeType == "TIMESTAMP" ->
                dateTimeFromString(scope, core, value)
            normalizedNativeType == "TIMESTAMP WITH TIME ZONE" ||
                normalizedNativeType == "TIMESTAMPTZ" ||
                normalizedNativeType == "DATETIME WITH TIME ZONE" -> ObjInstant(Instant.parse(value.trim()))
            else -> ObjString(value)
        }
    }

    private fun readGeneratedKeys(
        core: SqliteCoreModule,
        clause: String,
        affectedRows: Int,
    ): SqliteResultSetData {
        if (affectedRows <= 0 || !looksLikeInsert(clause)) {
            return emptyResultSet()
        }
        return SqliteResultSetData(
            columns = listOf(
                SqliteColumnMeta(
                    name = "generated_key",
                    sqlType = core.sqlTypes.require("Int"),
                    nullable = false,
                    nativeType = "INTEGER",
                )
            ),
            rows = listOf(listOf(ObjInt.of(sqlite3_last_insert_rowid(db))))
        )
    }

    private fun columnText(stmt: CPointer<sqlite3_stmt>, index: Int): String {
        return sqlite3_column_text(stmt, index)?.reinterpret<ByteVar>()?.toKString().orEmpty()
    }

    private fun sqlError(scope: ScopeFacade, core: SqliteCoreModule, rc: Int): ExecutionError {
        val code = sqlite3_extended_errcode(db)
        val message = sqlite3_errmsg(db)?.toKString() ?: "SQLite error ($rc)"
        val exceptionClass = if ((code and 0xff) == SQLITE_CONSTRAINT) core.sqlConstraintException else core.sqlExecutionException
        return ExecutionError(
            ObjException(exceptionClass, scope.requireScope(), ObjString(message)),
            scope.pos,
            message,
        )
    }
}

private fun openHandle(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    options: SqliteOpenOptions,
): NativeSqliteHandle = memScoped {
    val flags = buildOpenFlags(options)
    val db = lyng_sqlite3_open(options.path, flags)
    val rc = db?.let { sqlite3_extended_errcode(it) } ?: SQLITE_OK
    if (db == null || rc != SQLITE_OK) {
        val message = db?.let { sqlite3_errmsg(it)?.toKString() } ?: "SQLite open failed"
        if (db != null) {
            sqlite3_close_v2(db)
        }
        throw databaseError(scope, core, message)
    }
    sqlite3_extended_result_codes(db, 1)
    if (sqlite3_busy_timeout(db, options.busyTimeoutMillis) != SQLITE_OK) {
        val message = sqlite3_errmsg(db)?.toKString() ?: "Failed to configure SQLite busy timeout"
        sqlite3_close_v2(db)
        throw databaseError(scope, core, message)
    }
    val handle = NativeSqliteHandle(db)
    try {
        handle.execUnit(scope, core, if (options.foreignKeys) "pragma foreign_keys = on" else "pragma foreign_keys = off")
    } catch (e: Throwable) {
        handle.close()
        throw e
    }
    handle
}

private fun buildOpenFlags(options: SqliteOpenOptions): Int {
    var flags = SQLITE_OPEN_URI
    if (options.readOnly) {
        flags = flags or SQLITE_OPEN_READONLY
    } else {
        flags = flags or SQLITE_OPEN_READWRITE
        if (options.createIfMissing) {
            flags = flags or SQLITE_OPEN_CREATE
        }
    }
    return flags
}

private fun containsRowReturningClause(clause: String): Boolean =
    Regex("""\breturning\b""", RegexOption.IGNORE_CASE).containsMatchIn(clause)

private fun looksLikeInsert(clause: String): Boolean = clause.trimStart().startsWith("insert", ignoreCase = true)

private val SQLITE_TRANSIENT = (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()

private fun emptyResultSet(): SqliteResultSetData = SqliteResultSetData(emptyList(), emptyList())

private fun mapSqlType(core: SqliteCoreModule, nativeType: String, sqliteType: Int): ObjEnumEntry = when (val normalized = normalizeDeclaredTypeName(nativeType)) {
    "BOOLEAN", "BOOL" -> core.sqlTypes.require("Bool")
    "DATE" -> core.sqlTypes.require("Date")
    "DATETIME", "TIMESTAMP" -> core.sqlTypes.require("DateTime")
    "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ", "DATETIME WITH TIME ZONE" -> core.sqlTypes.require("Instant")
    "DECIMAL", "NUMERIC" -> core.sqlTypes.require("Decimal")
    "TIME", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE" -> core.sqlTypes.require("String")
    else -> when {
        normalized.contains("BLOB") -> core.sqlTypes.require("Binary")
        normalized.contains("INT") -> core.sqlTypes.require("Int")
        normalized.contains("CHAR") || normalized.contains("TEXT") || normalized.contains("CLOB") -> core.sqlTypes.require("String")
        normalized.contains("REAL") || normalized.contains("FLOA") || normalized.contains("DOUB") -> core.sqlTypes.require("Double")
        sqliteType == SQLITE_INTEGER -> core.sqlTypes.require("Int")
        sqliteType == SQLITE_FLOAT -> core.sqlTypes.require("Double")
        sqliteType == SQLITE_BLOB -> core.sqlTypes.require("Binary")
        else -> core.sqlTypes.require("String")
    }
}

private fun isDecimalNativeType(normalizedNativeType: String): Boolean =
    normalizedNativeType == "DECIMAL" || normalizedNativeType == "NUMERIC"

private fun isBooleanNativeType(normalizedNativeType: String): Boolean =
    normalizedNativeType == "BOOLEAN" || normalizedNativeType == "BOOL"

private suspend fun decimalFromString(scope: ScopeFacade, value: String): Obj {
    val decimalModule = scope.requireScope().currentImportProvider.createModuleScope(scope.pos, "lyng.decimal")
    val decimalClass = decimalModule.requireClass("Decimal")
    return decimalClass.invokeInstanceMethod(scope.requireScope(), "fromString", ObjString(value))
}

private fun dateTimeFromString(scope: ScopeFacade, core: SqliteCoreModule, value: String): ObjDateTime {
    val trimmed = value.trim()
    if (hasExplicitTimeZone(trimmed)) {
        throw sqlExecutionError(scope, core, "SQLite TIMESTAMP/DATETIME value must not contain a timezone offset: $value")
    }
    val local = LocalDateTime.parse(trimmed)
    return ObjDateTime(local.toInstant(TimeZone.UTC), TimeZone.UTC)
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

private fun raiseExecuteReturningUsage(scope: ScopeFacade, core: SqliteCoreModule): Nothing {
    scope.raiseError(
        ObjException(
            core.sqlUsageException,
            scope.requireScope(),
            ObjString("execute(...) cannot be used with statements that return rows; use select(...)")
        )
    )
}

private fun usageError(scope: ScopeFacade, core: SqliteCoreModule, message: String): ExecutionError {
    return ExecutionError(
        ObjException(core.sqlUsageException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
    )
}

private fun databaseError(scope: ScopeFacade, core: SqliteCoreModule, message: String): ExecutionError {
    return ExecutionError(
        ObjException(core.databaseException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
    )
}

private fun integerToBool(scope: ScopeFacade, core: SqliteCoreModule, value: Long): Obj =
    when (value) {
        0L -> ObjBool(false)
        1L -> ObjBool(true)
        else -> throw sqlExecutionError(scope, core, "Invalid SQLite boolean value: $value")
    }

private fun stringToBool(scope: ScopeFacade, core: SqliteCoreModule, value: String): Obj =
    when (value.trim().lowercase()) {
        "true", "t" -> ObjBool(true)
        "false", "f" -> ObjBool(false)
        else -> throw sqlExecutionError(scope, core, "Invalid SQLite boolean value: $value")
    }

private fun normalizeDeclaredTypeName(nativeTypeName: String): String {
    val strippedSuffix = nativeTypeName.trim().replace(Regex("""\s*\(.*\)\s*$"""), "")
    return strippedSuffix.uppercase().replace(Regex("""\s+"""), " ").trim()
}

private fun sqlExecutionError(scope: ScopeFacade, core: SqliteCoreModule, message: String): ExecutionError {
    return ExecutionError(
        ObjException(core.sqlExecutionException, scope.requireScope(), ObjString(message)),
        scope.pos,
        message,
    )
}
