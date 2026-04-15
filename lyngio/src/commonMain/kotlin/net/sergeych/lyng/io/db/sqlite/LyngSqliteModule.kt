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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.requireScope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjImmutableList
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyngio.stdlib_included.db_sqliteLyng

private const val SQLITE_MODULE_NAME = "lyng.io.db.sqlite"
private const val DB_MODULE_NAME = "lyng.io.db"

fun createSqliteModule(scope: Scope): Boolean = createSqliteModule(scope.importManager)

fun createSqlite(scope: Scope): Boolean = createSqliteModule(scope)

fun createSqliteModule(manager: ImportManager): Boolean {
    createDbModule(manager)
    if (manager.packageNames.contains(SQLITE_MODULE_NAME)) return false
    manager.addPackage(SQLITE_MODULE_NAME) { module ->
        buildSqliteModule(module)
    }
    return true
}

fun createSqlite(manager: ImportManager): Boolean = createSqliteModule(manager)

private suspend fun buildSqliteModule(module: ModuleScope) {
    module.eval(Source(SQLITE_MODULE_NAME, db_sqliteLyng))
    val dbModule = module.importProvider.createModuleScope(Pos.builtIn, DB_MODULE_NAME)
    val core = SqliteCoreModule.resolve(dbModule)
    val runtimeTypes = SqliteRuntimeTypes.create(core)

    module.addFn("openSqlite") {
        val options = parseOpenSqliteArgs(this)
        SqliteDatabaseObj(runtimeTypes, openSqliteBackend(this, core, options))
    }

    dbModule.callFn(
        "registerDatabaseProvider",
        ObjString("sqlite"),
        net.sergeych.lyng.obj.ObjExternCallable.fromBridge {
            val connectionUrl = requiredArg<ObjString>(0).value
            val extraParams = args.list.getOrNull(1)
                ?: raiseError("Expected exactly 2 arguments, got ${args.list.size}")
            val options = parseSqliteConnectionUrl(this, connectionUrl, extraParams)
            SqliteDatabaseObj(runtimeTypes, openSqliteBackend(this, core, options))
        }
    )
}

private suspend fun parseOpenSqliteArgs(scope: ScopeFacade): SqliteOpenOptions {
    val pathValue = readArg(scope, "path", 0) ?: scope.raiseError("argument 'path' is required")
    val path = (pathValue as? ObjString)?.value ?: scope.raiseClassCastError("path must be String")
    val readOnly = readBoolArg(scope, "readOnly", 1, false)
    val createIfMissing = readBoolArg(scope, "createIfMissing", 2, true)
    val foreignKeys = readBoolArg(scope, "foreignKeys", 3, true)
    val busyTimeoutMillis = readIntArg(scope, "busyTimeoutMillis", 4, 5000)
    return SqliteOpenOptions(
        path = normalizeSqlitePath(path, scope),
        readOnly = readOnly,
        createIfMissing = createIfMissing,
        foreignKeys = foreignKeys,
        busyTimeoutMillis = busyTimeoutMillis,
    )
}

private suspend fun parseSqliteConnectionUrl(
    scope: ScopeFacade,
    connectionUrl: String,
    extraParams: Obj,
): SqliteOpenOptions {
    val prefix = "sqlite:"
    if (!connectionUrl.startsWith(prefix, ignoreCase = true)) {
        scope.raiseIllegalArgument("Malformed SQLite connection URL: $connectionUrl")
    }
    val rawPath = connectionUrl.substring(prefix.length)
    val path = normalizeSqlitePath(rawPath, scope)
    val readOnly = mapBool(extraParams, scope, "readOnly") ?: false
    val createIfMissing = mapBool(extraParams, scope, "createIfMissing") ?: true
    val foreignKeys = mapBool(extraParams, scope, "foreignKeys") ?: true
    val busyTimeoutMillis = mapInt(extraParams, scope, "busyTimeoutMillis") ?: 5000
    return SqliteOpenOptions(
        path = path,
        readOnly = readOnly,
        createIfMissing = createIfMissing,
        foreignKeys = foreignKeys,
        busyTimeoutMillis = busyTimeoutMillis,
    )
}

private fun normalizeSqlitePath(rawPath: String, scope: ScopeFacade): String {
    val path = rawPath.trim()
    if (path.isEmpty()) {
        scope.raiseIllegalArgument("SQLite path must not be empty")
    }
    if (path.startsWith("//")) {
        scope.raiseIllegalArgument("Unsupported SQLite URL form: sqlite:$path")
    }
    return path
}

private suspend fun readArg(scope: ScopeFacade, name: String, position: Int): Obj? {
    val named = scope.args.named[name]
    val positional = scope.args.list.getOrNull(position)
    if (named != null && positional != null) {
        scope.raiseIllegalArgument("argument '$name' is already set")
    }
    return named ?: positional
}

private suspend fun readBoolArg(scope: ScopeFacade, name: String, position: Int, default: Boolean): Boolean {
    val value = readArg(scope, name, position) ?: return default
    return (value as? ObjBool)?.value ?: scope.raiseClassCastError("$name must be Bool")
}

private suspend fun readIntArg(scope: ScopeFacade, name: String, position: Int, default: Int): Int {
    val value = readArg(scope, name, position) ?: return default
    return when (value) {
        is ObjInt -> value.value.toInt()
        else -> scope.raiseClassCastError("$name must be Int")
    }
}

private suspend fun mapBool(map: Obj, scope: ScopeFacade, key: String): Boolean? {
    val value = map.getAt(scope.requireScope(), ObjString(key))
    return when (value) {
        ObjNull -> null
        is ObjBool -> value.value
        else -> scope.raiseClassCastError("extraParams.$key must be Bool")
    }
}

private suspend fun mapInt(map: Obj, scope: ScopeFacade, key: String): Int? {
    val value = map.getAt(scope.requireScope(), ObjString(key))
    return when (value) {
        ObjNull -> null
        is ObjInt -> value.value.toInt()
        else -> scope.raiseClassCastError("extraParams.$key must be Int")
    }
}

private suspend fun ModuleScope.callFn(name: String, vararg args: Obj): Obj {
    val callee = get(name)?.value ?: error("Missing $name in module")
    return callee.invoke(this, ObjNull, *args)
}

internal data class SqliteOpenOptions(
    val path: String,
    val readOnly: Boolean,
    val createIfMissing: Boolean,
    val foreignKeys: Boolean,
    val busyTimeoutMillis: Int,
)

internal data class SqliteColumnMeta(
    val name: String,
    val sqlType: ObjEnumEntry,
    val nullable: Boolean,
    val nativeType: String,
)

internal data class SqliteResultSetData(
    val columns: List<SqliteColumnMeta>,
    val rows: List<List<Obj>>,
)

internal data class SqliteExecutionResultData(
    val affectedRowsCount: Int,
    val generatedKeys: SqliteResultSetData,
)

internal interface SqliteDatabaseBackend {
    suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T
}

internal interface SqliteTransactionBackend {
    suspend fun select(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteResultSetData
    suspend fun execute(scope: ScopeFacade, clause: String, params: List<Obj>): SqliteExecutionResultData
    suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqliteTransactionBackend) -> T): T
}

internal expect suspend fun openSqliteBackend(
    scope: ScopeFacade,
    core: SqliteCoreModule,
    options: SqliteOpenOptions,
): SqliteDatabaseBackend

internal class SqliteCoreModule private constructor(
    val module: ModuleScope,
    val databaseClass: ObjClass,
    val transactionClass: ObjClass,
    val resultSetClass: ObjClass,
    val rowClass: ObjClass,
    val columnClass: ObjClass,
    val executionResultClass: ObjClass,
    val databaseException: ObjException.Companion.ExceptionClass,
    val sqlExecutionException: ObjException.Companion.ExceptionClass,
    val sqlConstraintException: ObjException.Companion.ExceptionClass,
    val sqlUsageException: ObjException.Companion.ExceptionClass,
    val rollbackException: ObjException.Companion.ExceptionClass,
    val sqlTypes: SqlTypeEntries,
) {
    companion object {
        fun resolve(module: ModuleScope): SqliteCoreModule = SqliteCoreModule(
            module = module,
            databaseClass = module.requireClass("Database"),
            transactionClass = module.requireClass("SqlTransaction"),
            resultSetClass = module.requireClass("ResultSet"),
            rowClass = module.requireClass("SqlRow"),
            columnClass = module.requireClass("SqlColumn"),
            executionResultClass = module.requireClass("ExecutionResult"),
            databaseException = module.requireClass("DatabaseException") as ObjException.Companion.ExceptionClass,
            sqlExecutionException = module.requireClass("SqlExecutionException") as ObjException.Companion.ExceptionClass,
            sqlConstraintException = module.requireClass("SqlConstraintException") as ObjException.Companion.ExceptionClass,
            sqlUsageException = module.requireClass("SqlUsageException") as ObjException.Companion.ExceptionClass,
            rollbackException = module.requireClass("RollbackException") as ObjException.Companion.ExceptionClass,
            sqlTypes = SqlTypeEntries.resolve(module),
        )
    }
}

internal class SqlTypeEntries private constructor(
    private val entries: Map<String, ObjEnumEntry>,
) {
    fun require(name: String): ObjEnumEntry = entries[name]
        ?: error("lyng.io.db.SqlType entry is missing: $name")

    companion object {
        fun resolve(module: ModuleScope): SqlTypeEntries {
            val enumClass = resolveEnum(module, "SqlType")
            return SqlTypeEntries(
                listOf(
                    "Binary", "String", "Int", "Double", "Decimal",
                    "Bool", "Instant", "Date", "DateTime"
                ).associateWith { name ->
                    enumClass.byName[ObjString(name)] as? ObjEnumEntry
                        ?: error("lyng.io.db.SqlType.$name is missing")
                }
            )
        }

        private fun resolveEnum(module: ModuleScope, enumName: String): ObjEnumClass {
            val local = module.get(enumName)?.value as? ObjEnumClass
            if (local != null) return local
            val root = module.importProvider.rootScope.get(enumName)?.value as? ObjEnumClass
            return root ?: error("lyng.io.db declaration enum is missing: $enumName")
        }
    }
}

private class SqliteRuntimeTypes private constructor(
    val core: SqliteCoreModule,
    val databaseClass: ObjClass,
    val transactionClass: ObjClass,
    val resultSetClass: ObjClass,
    val rowClass: ObjClass,
    val columnClass: ObjClass,
    val executionResultClass: ObjClass,
) {
    companion object {
        fun create(core: SqliteCoreModule): SqliteRuntimeTypes {
            val databaseClass = object : ObjClass("SqliteDatabase", core.databaseClass) {}
            val transactionClass = object : ObjClass("SqliteTransaction", core.transactionClass) {}
            val resultSetClass = object : ObjClass("SqliteResultSet", core.resultSetClass) {}
            val rowClass = object : ObjClass("SqliteRow", core.rowClass) {}
            val columnClass = object : ObjClass("SqliteColumn", core.columnClass) {}
            val executionResultClass = object : ObjClass("SqliteExecutionResult", core.executionResultClass) {}
            val runtime = SqliteRuntimeTypes(
                core = core,
                databaseClass = databaseClass,
                transactionClass = transactionClass,
                resultSetClass = resultSetClass,
                rowClass = rowClass,
                columnClass = columnClass,
                executionResultClass = executionResultClass,
            )
            runtime.bind()
            return runtime
        }
    }

    private fun bind() {
        databaseClass.addFn("transaction") {
            val self = thisAs<SqliteDatabaseObj>()
            val block = args.list.getOrNull(0) ?: raiseError("Expected exactly 1 argument, got ${args.list.size}")
            if (!block.isInstanceOf("Callable")) {
                raiseClassCastError("transaction block must be callable")
            }
            self.backend.transaction(this) { backend ->
                val lifetime = TransactionLifetime(this@SqliteRuntimeTypes.core)
                try {
                    call(block, Arguments(SqliteTransactionObj(this@SqliteRuntimeTypes, backend, lifetime)), ObjNull)
                } finally {
                    lifetime.close()
                }
            }
        }

        transactionClass.addFn("select") {
            val self = thisAs<SqliteTransactionObj>()
            self.lifetime.ensureActive(this)
            val clause = requiredArg<ObjString>(0).value
            val params = args.list.drop(1)
            SqliteResultSetObj(thisAs<SqliteTransactionObj>().types, self.lifetime, self.backend.select(this, clause, params))
        }
        transactionClass.addFn("execute") {
            val self = thisAs<SqliteTransactionObj>()
            self.lifetime.ensureActive(this)
            val clause = requiredArg<ObjString>(0).value
            val params = args.list.drop(1)
            SqliteExecutionResultObj(self.types, self.lifetime, self.backend.execute(this, clause, params))
        }
        transactionClass.addFn("transaction") {
            val self = thisAs<SqliteTransactionObj>()
            self.lifetime.ensureActive(this)
            val block = args.list.getOrNull(0) ?: raiseError("Expected exactly 1 argument, got ${args.list.size}")
            if (!block.isInstanceOf("Callable")) {
                raiseClassCastError("transaction block must be callable")
            }
            self.backend.transaction(this) { backend ->
                val lifetime = TransactionLifetime(this@SqliteRuntimeTypes.core)
                try {
                    call(block, Arguments(SqliteTransactionObj(self.types, backend, lifetime)), ObjNull)
                } finally {
                    lifetime.close()
                }
            }
        }

        resultSetClass.addProperty("columns", getter = {
            val self = thisAs<SqliteResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.columns)
        })
        resultSetClass.addFn("size") {
            val self = thisAs<SqliteResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjInt.of(self.rows.size.toLong())
        }
        resultSetClass.addFn("isEmpty") {
            val self = thisAs<SqliteResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjBool(self.rows.isEmpty())
        }
        resultSetClass.addFn("iterator") {
            val self = thisAs<SqliteResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.rows).invokeInstanceMethod(requireScope(), "iterator")
        }
        resultSetClass.addFn("toList") {
            val self = thisAs<SqliteResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.rows)
        }

        rowClass.addProperty("size", getter = {
            val self = thisAs<SqliteRowObj>()
            self.lifetime.ensureActive(this)
            ObjInt.of(self.values.size.toLong())
        })
        rowClass.addProperty("values", getter = {
            val self = thisAs<SqliteRowObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.values)
        })

        columnClass.addProperty("name", getter = { ObjString(thisAs<SqliteColumnObj>().meta.name) })
        columnClass.addProperty("sqlType", getter = { thisAs<SqliteColumnObj>().meta.sqlType })
        columnClass.addProperty("nullable", getter = { ObjBool(thisAs<SqliteColumnObj>().meta.nullable) })
        columnClass.addProperty("nativeType", getter = { ObjString(thisAs<SqliteColumnObj>().meta.nativeType) })

        executionResultClass.addProperty("affectedRowsCount", getter = {
            val self = thisAs<SqliteExecutionResultObj>()
            self.lifetime.ensureActive(this)
            ObjInt.of(self.result.affectedRowsCount.toLong())
        })
        executionResultClass.addFn("getGeneratedKeys") {
            val self = thisAs<SqliteExecutionResultObj>()
            self.lifetime.ensureActive(this)
            SqliteResultSetObj(self.types, self.lifetime, self.result.generatedKeys)
        }
    }
}

private class TransactionLifetime(
    private val core: SqliteCoreModule,
) {
    private var active = true

    fun close() {
        active = false
    }

    fun ensureActive(scope: ScopeFacade) {
        if (!active) {
            scope.raiseError(
                ObjException(core.sqlUsageException, scope.requireScope(), ObjString("SQL result can be used only while its transaction is active"))
            )
        }
    }
}

private class SqliteDatabaseObj(
    val types: SqliteRuntimeTypes,
    val backend: SqliteDatabaseBackend,
) : Obj() {
    override val objClass: ObjClass
        get() = types.databaseClass
}

private class SqliteTransactionObj(
    val types: SqliteRuntimeTypes,
    val backend: SqliteTransactionBackend,
    val lifetime: TransactionLifetime,
) : Obj() {
    override val objClass: ObjClass
        get() = types.transactionClass
}

private class SqliteResultSetObj(
    val types: SqliteRuntimeTypes,
    val lifetime: TransactionLifetime,
    data: SqliteResultSetData,
) : Obj() {
    val columns: List<Obj> = data.columns.map { SqliteColumnObj(types, it) }
    val rows: List<Obj> = buildRows(types, lifetime, data)

    override val objClass: ObjClass
        get() = types.resultSetClass

    private fun buildRows(
        types: SqliteRuntimeTypes,
        lifetime: TransactionLifetime,
        data: SqliteResultSetData,
    ): List<Obj> {
        val indexByName = linkedMapOf<String, MutableList<Int>>()
        data.columns.forEachIndexed { index, column ->
            indexByName.getOrPut(column.name.lowercase()) { mutableListOf() }.add(index)
        }
        return data.rows.map { rowValues ->
            SqliteRowObj(types, lifetime, rowValues, indexByName)
        }
    }
}

private class SqliteRowObj(
    val types: SqliteRuntimeTypes,
    val lifetime: TransactionLifetime,
    val values: List<Obj>,
    private val indexByName: Map<String, List<Int>>,
) : Obj() {
    override val objClass: ObjClass
        get() = types.rowClass

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        lifetime.ensureActive(scope.asFacade())
        return when (index) {
            is ObjInt -> {
                val idx = index.value.toInt()
                if (idx !in values.indices) {
                    scope.raiseIndexOutOfBounds("SQL row index $idx is out of bounds")
                }
                values[idx]
            }
            is ObjString -> {
                val matches = indexByName[index.value.lowercase()]
                    ?: scope.raiseError(
                        ObjException(
                            types.core.sqlUsageException,
                            scope,
                            ObjString("No such SQL result column: ${index.value}")
                        )
                    )
                if (matches.size != 1) {
                    scope.raiseError(
                        ObjException(
                            types.core.sqlUsageException,
                            scope,
                            ObjString("Ambiguous SQL result column: ${index.value}")
                        )
                    )
                }
                values[matches.first()]
            }
            else -> scope.raiseClassCastError("SQL row index must be Int or String")
        }
    }
}

private class SqliteColumnObj(
    val types: SqliteRuntimeTypes,
    val meta: SqliteColumnMeta,
) : Obj() {
    override val objClass: ObjClass
        get() = types.columnClass
}

private class SqliteExecutionResultObj(
    val types: SqliteRuntimeTypes,
    val lifetime: TransactionLifetime,
    val result: SqliteExecutionResultData,
) : Obj() {
    override val objClass: ObjClass
        get() = types.executionResultClass
}
