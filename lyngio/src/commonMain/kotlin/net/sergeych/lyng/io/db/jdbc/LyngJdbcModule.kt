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

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.io.db.SqlCoreModule
import net.sergeych.lyng.io.db.SqlDatabaseBackend
import net.sergeych.lyng.io.db.SqlDatabaseObj
import net.sergeych.lyng.io.db.SqlRuntimeTypes
import net.sergeych.lyng.io.db.createDbModule
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjImmutableMap
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.stdlib_included.db_jdbcLyng

private const val JDBC_MODULE_NAME = "lyng.io.db.jdbc"
private const val DB_MODULE_NAME = "lyng.io.db"
private const val JDBC_SCHEME = "jdbc"
private const val H2_DRIVER = "org.h2.Driver"
private const val POSTGRES_DRIVER = "org.postgresql.Driver"

fun createJdbcModule(scope: Scope): Boolean = createJdbcModule(scope.importManager)

fun createJdbc(scope: Scope): Boolean = createJdbcModule(scope)

fun createJdbcModule(manager: ImportManager): Boolean {
    createDbModule(manager)
    if (manager.packageNames.contains(JDBC_MODULE_NAME)) return false
    manager.addPackage(JDBC_MODULE_NAME) { module ->
        buildJdbcModule(module)
    }
    return true
}

fun createJdbc(manager: ImportManager): Boolean = createJdbcModule(manager)

private suspend fun buildJdbcModule(module: ModuleScope) {
    module.eval(Source(JDBC_MODULE_NAME, db_jdbcLyng))
    val dbModule = module.importProvider.createModuleScope(Pos.builtIn, DB_MODULE_NAME)
    val core = SqlCoreModule.resolve(dbModule)
    val runtimeTypes = SqlRuntimeTypes.create("Jdbc", core)

    module.addFn("openJdbc") {
        val options = parseOpenJdbcArgs(this)
        SqlDatabaseObj(runtimeTypes, openJdbcBackend(this, core, options))
    }
    module.addFn("openH2") {
        val options = parseOpenShortcutArgs(this, H2_DRIVER, ::normalizeH2Url)
        SqlDatabaseObj(runtimeTypes, openJdbcBackend(this, core, options))
    }
    module.addFn("openPostgres") {
        val options = parseOpenShortcutArgs(this, POSTGRES_DRIVER, ::normalizePostgresUrl)
        SqlDatabaseObj(runtimeTypes, openJdbcBackend(this, core, options))
    }

    registerProvider(dbModule, runtimeTypes, core, JDBC_SCHEME, null)
    registerProvider(dbModule, runtimeTypes, core, "h2", H2_DRIVER)
    registerProvider(dbModule, runtimeTypes, core, "postgres", POSTGRES_DRIVER)
    registerProvider(dbModule, runtimeTypes, core, "postgresql", POSTGRES_DRIVER)
}

private suspend fun registerProvider(
    dbModule: ModuleScope,
    runtimeTypes: SqlRuntimeTypes,
    core: SqlCoreModule,
    scheme: String,
    implicitDriverClass: String?,
) {
    dbModule.callFn(
        "registerDatabaseProvider",
        ObjString(scheme),
        net.sergeych.lyng.obj.ObjExternCallable.fromBridge {
            val connectionUrl = requiredArg<ObjString>(0).value
            val extraParams = args.list.getOrNull(1)
                ?: raiseError("Expected exactly 2 arguments, got ${args.list.size}")
            val options = parseJdbcConnectionUrl(this, scheme, implicitDriverClass, connectionUrl, extraParams)
            SqlDatabaseObj(runtimeTypes, openJdbcBackend(this, core, options))
        }
    )
}

private suspend fun parseOpenJdbcArgs(scope: ScopeFacade): JdbcOpenOptions {
    val rawUrl = readArg(scope, "connectionUrl", 0) ?: scope.raiseError("argument 'connectionUrl' is required")
    val connectionUrl = (rawUrl as? ObjString)?.value ?: scope.raiseClassCastError("connectionUrl must be String")
    val user = readNullableStringArg(scope, "user", 1)
    val password = readNullableStringArg(scope, "password", 2)
    val driverClass = readNullableStringArg(scope, "driverClass", 3)
    val properties = readPropertiesArg(scope, "properties", 4)
    return JdbcOpenOptions(
        connectionUrl = normalizeJdbcUrl(connectionUrl, scope),
        user = user,
        password = password,
        driverClass = driverClass,
        properties = properties,
    )
}

private suspend fun parseOpenShortcutArgs(
    scope: ScopeFacade,
    defaultDriverClass: String,
    normalize: (String, ScopeFacade) -> String,
): JdbcOpenOptions {
    val rawUrl = readArg(scope, "connectionUrl", 0) ?: scope.raiseError("argument 'connectionUrl' is required")
    val connectionUrl = (rawUrl as? ObjString)?.value ?: scope.raiseClassCastError("connectionUrl must be String")
    val user = readNullableStringArg(scope, "user", 1)
    val password = readNullableStringArg(scope, "password", 2)
    val properties = readPropertiesArg(scope, "properties", 3)
    return JdbcOpenOptions(
        connectionUrl = normalize(connectionUrl, scope),
        user = user,
        password = password,
        driverClass = defaultDriverClass,
        properties = properties,
    )
}

private suspend fun parseJdbcConnectionUrl(
    scope: ScopeFacade,
    scheme: String,
    implicitDriverClass: String?,
    connectionUrl: String,
    extraParams: Obj,
): JdbcOpenOptions {
    val driverClass = mapNullableString(extraParams, scope, "driverClass") ?: implicitDriverClass
    val user = mapNullableString(extraParams, scope, "user")
    val password = mapNullableString(extraParams, scope, "password")
    val properties = mapProperties(extraParams, scope, "properties")
    val normalizedUrl = when (scheme) {
        JDBC_SCHEME -> normalizeJdbcUrl(connectionUrl, scope)
        "h2" -> normalizeH2Url(connectionUrl, scope)
        "postgres", "postgresql" -> normalizePostgresUrl(connectionUrl, scope)
        else -> scope.raiseIllegalArgument("Unsupported JDBC provider scheme: $scheme")
    }
    return JdbcOpenOptions(
        connectionUrl = normalizedUrl,
        user = user,
        password = password,
        driverClass = driverClass,
        properties = properties,
    )
}

private fun normalizeJdbcUrl(rawUrl: String, scope: ScopeFacade): String {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("jdbc:", ignoreCase = true)) {
        scope.raiseIllegalArgument("JDBC connection URL must start with jdbc:")
    }
    return trimmed
}

private fun normalizeH2Url(rawUrl: String, scope: ScopeFacade): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        scope.raiseIllegalArgument("H2 connection URL must not be empty")
    }
    return when {
        trimmed.startsWith("jdbc:h2:", ignoreCase = true) -> trimmed
        trimmed.startsWith("h2:", ignoreCase = true) -> "jdbc:${trimmed}"
        else -> "jdbc:h2:$trimmed"
    }
}

private fun normalizePostgresUrl(rawUrl: String, scope: ScopeFacade): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        scope.raiseIllegalArgument("PostgreSQL connection URL must not be empty")
    }
    return when {
        trimmed.startsWith("jdbc:postgresql:", ignoreCase = true) -> trimmed
        trimmed.startsWith("postgresql:", ignoreCase = true) -> "jdbc:$trimmed"
        trimmed.startsWith("postgres:", ignoreCase = true) -> "jdbc:postgresql:${trimmed.substringAfter(':')}"
        else -> "jdbc:postgresql:$trimmed"
    }
}

private suspend fun readArg(scope: ScopeFacade, name: String, position: Int): Obj? {
    val named = scope.args.named[name]
    val positional = scope.args.list.getOrNull(position)
    if (named != null && positional != null) {
        scope.raiseIllegalArgument("argument '$name' is already set")
    }
    return named ?: positional
}

private suspend fun readNullableStringArg(scope: ScopeFacade, name: String, position: Int): String? {
    val value = readArg(scope, name, position) ?: return null
    return when (value) {
        ObjNull -> null
        is ObjString -> value.value
        else -> scope.raiseClassCastError("$name must be String?")
    }
}

private suspend fun readPropertiesArg(scope: ScopeFacade, name: String, position: Int): Map<String, String> {
    val value = readArg(scope, name, position) ?: return emptyMap()
    return when (value) {
        ObjNull -> emptyMap()
        else -> objToStringMap(value, scope, name)
    }
}

private suspend fun mapNullableString(map: Obj, scope: ScopeFacade, key: String): String? {
    val value = map.getAt(scope.requireScope(), ObjString(key))
    return when (value) {
        ObjNull -> null
        is ObjString -> value.value
        else -> scope.raiseClassCastError("extraParams.$key must be String?")
    }
}

private suspend fun mapProperties(map: Obj, scope: ScopeFacade, key: String): Map<String, String> {
    val value = map.getAt(scope.requireScope(), ObjString(key))
    return when (value) {
        ObjNull -> emptyMap()
        else -> objToStringMap(value, scope, "extraParams.$key")
    }
}

private suspend fun objToStringMap(value: Obj, scope: ScopeFacade, label: String): Map<String, String> {
    val rawEntries = when (value) {
        is ObjMap -> value.map
        is ObjImmutableMap -> value.map
        else -> scope.raiseClassCastError("$label must be Map<String, Object?>")
    }
    val properties = linkedMapOf<String, String>()
    for ((rawKey, rawValue) in rawEntries) {
        val key = (rawKey as? ObjString)?.value ?: scope.raiseClassCastError("$label keys must be String")
        if (rawValue == ObjNull) continue
        properties[key] = scope.toStringOf(rawValue).value
    }
    return properties
}

private suspend fun ModuleScope.callFn(name: String, vararg args: Obj): Obj {
    val callee = get(name)?.value ?: error("Missing $name in module")
    return callee.invoke(this, ObjNull, *args)
}

internal data class JdbcOpenOptions(
    val connectionUrl: String,
    val user: String?,
    val password: String?,
    val driverClass: String?,
    val properties: Map<String, String>,
)

internal expect suspend fun openJdbcBackend(
    scope: ScopeFacade,
    core: SqlCoreModule,
    options: JdbcOpenOptions,
): SqlDatabaseBackend
