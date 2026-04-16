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
import net.sergeych.lyng.io.db.SqlCoreModule
import net.sergeych.lyng.io.db.SqlDatabaseBackend
import net.sergeych.lyng.io.db.SqlDatabaseObj
import net.sergeych.lyng.io.db.SqlRuntimeTypes
import net.sergeych.lyng.io.db.SqlTransactionBackend
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.requireScope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
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
    val core = SqlCoreModule.resolve(dbModule)
    val runtimeTypes = SqlRuntimeTypes.create("Sqlite", core)

    module.addFn("openSqlite") {
        val options = parseOpenSqliteArgs(this)
        SqlDatabaseObj(runtimeTypes, openSqliteBackend(this, core, options))
    }

    dbModule.callFn(
        "registerDatabaseProvider",
        ObjString("sqlite"),
        net.sergeych.lyng.obj.ObjExternCallable.fromBridge {
            val connectionUrl = requiredArg<ObjString>(0).value
            val extraParams = args.list.getOrNull(1)
                ?: raiseError("Expected exactly 2 arguments, got ${args.list.size}")
            val options = parseSqliteConnectionUrl(this, connectionUrl, extraParams)
            SqlDatabaseObj(runtimeTypes, openSqliteBackend(this, core, options))
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

internal expect suspend fun openSqliteBackend(
    scope: ScopeFacade,
    core: SqlCoreModule,
    options: SqliteOpenOptions,
): SqlDatabaseBackend
