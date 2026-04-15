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

package net.sergeych.lyng.io.db

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Source
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.requireScope
import net.sergeych.lyngio.stdlib_included.dbLyng

private const val DB_MODULE_NAME = "lyng.io.db"

fun createDbModule(scope: Scope): Boolean = createDbModule(scope.importManager)

fun createDb(scope: Scope): Boolean = createDbModule(scope)

fun createDbModule(manager: ImportManager): Boolean {
    if (manager.packageNames.contains(DB_MODULE_NAME)) return false
    manager.addPackage(DB_MODULE_NAME) { module ->
        buildDbModule(module)
    }
    return true
}

fun createDb(manager: ImportManager): Boolean = createDbModule(manager)

private suspend fun buildDbModule(module: ModuleScope) {
    module.eval(Source(DB_MODULE_NAME, dbLyng))
    val exceptions = installDbExceptionClasses(module)
    val registry = DbProviderRegistry()

    module.addFn("registerDatabaseProvider") {
        val scheme = requiredArg<ObjString>(0).value
        val opener = args.list.getOrNull(1)
            ?: raiseError("Expected exactly 2 arguments, got ${args.list.size}")
        registry.register(this, scheme, opener)
        ObjVoid
    }

    module.addFn("openDatabase") {
        val connectionUrl = requiredArg<ObjString>(0).value
        val extraParams = args.list.getOrNull(1)
            ?: raiseError("Expected exactly 2 arguments, got ${args.list.size}")
        if (!extraParams.isInstanceOf("Map")) {
            raiseIllegalArgument("extraParams must be Map")
        }
        val scheme = parseConnectionScheme(connectionUrl)
            ?: raiseIllegalArgument("Malformed database connection URL: $connectionUrl")
        val opener = registry.providers[scheme]
            ?: raiseDatabaseException(exceptions.database, "No database provider registered for scheme '$scheme'")
        call(opener, Arguments(listOf(ObjString(connectionUrl), extraParams)), newThisObj = ObjNull)
    }
}

private data class DbExceptionClasses(
    val database: ObjException.Companion.ExceptionClass,
    val sqlExecution: ObjException.Companion.ExceptionClass,
    val sqlConstraint: ObjException.Companion.ExceptionClass,
    val sqlUsage: ObjException.Companion.ExceptionClass,
    val rollback: ObjException.Companion.ExceptionClass,
)

private fun installDbExceptionClasses(module: ModuleScope): DbExceptionClasses {
    val database = ObjException.Companion.ExceptionClass("DatabaseException", ObjException.Root)
    val sqlExecution = ObjException.Companion.ExceptionClass("SqlExecutionException", database)
    val sqlConstraint = ObjException.Companion.ExceptionClass("SqlConstraintException", sqlExecution)
    val sqlUsage = ObjException.Companion.ExceptionClass("SqlUsageException", database)
    val rollback = ObjException.Companion.ExceptionClass("RollbackException", ObjException.Root)

    module.addConst("DatabaseException", database)
    module.addConst("SqlExecutionException", sqlExecution)
    module.addConst("SqlConstraintException", sqlConstraint)
    module.addConst("SqlUsageException", sqlUsage)
    module.addConst("RollbackException", rollback)

    return DbExceptionClasses(
        database = database,
        sqlExecution = sqlExecution,
        sqlConstraint = sqlConstraint,
        sqlUsage = sqlUsage,
        rollback = rollback,
    )
}

private class DbProviderRegistry {
    val providers: MutableMap<String, Obj> = linkedMapOf()

    fun register(scope: ScopeFacade, rawScheme: String, opener: Obj) {
        val scheme = normalizeScheme(rawScheme)
            ?: scope.raiseIllegalArgument("Database provider scheme must not be empty")
        if (!opener.isInstanceOf("Callable")) {
            scope.raiseIllegalArgument("Database provider opener must be callable")
        }
        if (providers.containsKey(scheme)) {
            scope.raiseIllegalState("Database provider already registered for scheme '$scheme'")
        }
        providers[scheme] = opener
    }
}

private fun normalizeScheme(rawScheme: String): String? {
    val trimmed = rawScheme.trim()
    if (trimmed.isEmpty()) return null
    if (':' in trimmed) return null
    return trimmed.lowercase()
}

private fun parseConnectionScheme(connectionUrl: String): String? {
    val colonIndex = connectionUrl.indexOf(':')
    if (colonIndex <= 0) return null
    return normalizeScheme(connectionUrl.substring(0, colonIndex))
}

private fun ScopeFacade.raiseDatabaseException(
    exceptionClass: ObjException.Companion.ExceptionClass,
    message: String,
): Nothing = raiseError(ObjException(exceptionClass, requireScope(), ObjString(message)))
