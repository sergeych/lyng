/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng.pacman

import net.sergeych.lyng.*
import net.sergeych.mptools.CachedExpression

/**
 * Package manager INTERFACE (abstract class). Performs import routines
 * using abstract [createModuleScope] method ot be implemented by heirs.
 *
 * Notice that [createModuleScope] is responsible for caching the modules;
 * base class relies on caching. This is not implemented here as the correct
 * caching strategy depends on the import provider
 */
abstract class ImportProvider(
    val rootScope: Scope,
    val securityManager: SecurityManager = SecurityManager.allowAll
) {

    open fun getActualProvider() = this

    /**
     * Find an import and create a scope for it. This method must implement caching so repeated
     * imports are not repeatedly loaded and parsed and should be cheap.
     *
     * @throws ImportException if the module is not found
     */
    abstract suspend fun createModuleScope(pos: Pos,packageName: String): ModuleScope

    /**
     * Check that the import is possible and allowed. This method is called on compile time by [Compiler];
     * actual module loading is performed by [ModuleScope.importInto]
     */
    suspend fun prepareImport(pos: Pos, name: String, symbols: Map<String, String>?): ModuleScope {
        if (!securityManager.canImportModule(name))
            throw ImportException(pos, "Module $name is not allowed")
        symbols?.keys?.forEach {
            if (!securityManager.canImportSymbol(name, it)) throw ImportException(
                pos,
                "Symbol $name.$it is not allowed"
            )
        }
        return createModuleScope(pos, name)
    }

    fun newModule() = newModuleAt(Pos.builtIn)

    fun newModuleAt(pos: Pos): ModuleScope =
        ModuleScope(this, pos, "unknown")

    private var cachedStdScope = CachedExpression<Scope>()

    suspend fun newStdScope(pos: Pos = Pos.builtIn): Scope =
        cachedStdScope.get {
            newModuleAt(pos).also {
                it.eval("import lyng.stdlib\n")
            }
        }.copy()
}



