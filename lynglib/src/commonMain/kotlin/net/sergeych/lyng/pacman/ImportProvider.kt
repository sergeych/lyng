package net.sergeych.lyng.pacman

import net.sergeych.lyng.*

/**
 * Package manager INTERFACE (abstract class). Performs import routines
 * using abstract [createModuleScope] method ot be implemented by heirs.
 *
 * Notice that [createModuleScope] is responsible for caching the modules;
 * base class relies on caching. This is not implemented here as the correct
 * caching strategy depends on the import provider
 */
abstract class ImportProvider(
    val rootScope: Scope = Script.defaultScope,
    val securityManager: SecurityManager = SecurityManager.allowAll
) {
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

    companion object {
        val emptyAllowAll = object : ImportProvider(rootScope = Script.defaultScope, securityManager = SecurityManager.allowAll) {
            override suspend fun createModuleScope(pos: Pos,packageName: String): ModuleScope {
                throw ImportException(pos, "Empty import provider can't be used directly")
            }
        }
    }
}