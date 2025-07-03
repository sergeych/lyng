package net.sergeych.lyng

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Package manager. Chained manager, too simple. Override [createModuleScope] to return either
 * valid [ModuleScope] or call [parent] - or return null.
 */
abstract class Pacman(
    val parent: Pacman? = null,
    val rootScope: Scope = parent!!.rootScope,
    val securityManager: SecurityManager = parent!!.securityManager
) {
    private val opScopes = Mutex()

    private val cachedScopes = mutableMapOf<String, Scope>()

    /**
     * Create a new module scope if this pacman can import the module, return null otherwise so
     * the manager can decide what to do
     */
    abstract suspend fun createModuleScope(name: String): ModuleScope?

    suspend fun prepareImport(pos: Pos, name: String, symbols: Map<String, String>?) {
        if (!securityManager.canImportModule(name))
            throw ImportException(pos, "Module $name is not allowed")
        symbols?.keys?.forEach {
            if (!securityManager.canImportSymbol(name, it)) throw ImportException(
                pos,
                "Symbol $name.$it is not allowed"
            )
        }
        // if we can import the module, cache it, or go further
        opScopes.withLock {
            cachedScopes[name] ?: createModuleScope(name)?.let { cachedScopes[name] = it }
        } ?: parent?.prepareImport(pos, name, symbols) ?: throw ImportException(pos, "Module $name is not found")
    }

    suspend fun performImport(scope: Scope, name: String, symbols: Map<String, String>?) {
        val module = opScopes.withLock { cachedScopes[name] }
            ?: scope.raiseSymbolNotFound("module $name not found")
        val symbolsToImport = symbols?.keys?.toMutableSet()
        for ((symbol, record) in module.objects) {
            if (record.visibility.isPublic) {
                val newName = symbols?.let { ss: Map<String, String> ->
                    ss[symbol]
                        ?.also { symbolsToImport!!.remove(it) }
                        ?: scope.raiseError("internal error: symbol $symbol not found though the module is cached")
                } ?: symbol
                if (newName in scope.objects)
                    scope.raiseError("symbol $newName already exists, redefinition on import is not allowed")
                scope.objects[newName] = record
            }
        }
        if (!symbolsToImport.isNullOrEmpty())
            scope.raiseSymbolNotFound("symbols $name.{$symbolsToImport} are.were not found")
    }

    companion object {
        val emptyAllowAll = object : Pacman(rootScope = Script.defaultScope, securityManager = SecurityManager.allowAll) {
            override suspend fun createModuleScope(name: String): ModuleScope? {
                return null
            }

        }
    }

}