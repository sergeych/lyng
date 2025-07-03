package net.sergeych.lyng

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.mp_tools.globalDefer

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
                println("import $name: $symbol: $record")
                val newName = symbols?.let { ss: Map<String, String> ->
                    ss[symbol]
                        ?.also { symbolsToImport!!.remove(it) }
                        ?: scope.raiseError("internal error: symbol $symbol not found though the module is cached")
                } ?: symbol
                println("import $name.$symbol as $newName")
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

/**
 * Module scope supports importing and contains the [pacman]; it should be the same
 * used in [Compiler];
 */
class ModuleScope(
    val pacman: Pacman,
    pos: Pos = Pos.builtIn,
    val packageName: String
) : Scope(pacman.rootScope, Arguments.EMPTY, pos) {

    constructor(pacman: Pacman,source: Source) : this(pacman, source.startPos, source.fileName)

    override suspend fun checkImport(pos: Pos, name: String, symbols: Map<String, String>?) {
        pacman.prepareImport(pos, name, symbols)
    }

    /**
     * Import symbols into the scope. It _is called_ after the module is imported by [checkImport].
     * If [checkImport] was not called, the symbols will not be imported with exception as module is not found.
     */
    override suspend fun importInto(scope: Scope, name: String, symbols: Map<String, String>?) {
        pacman.performImport(scope, name, symbols)
    }

    val packageNameObj by lazy { ObjString(packageName).asReadonly}

    override fun get(name: String): ObjRecord? {
        return if( name == "__PACKAGE__")
            packageNameObj
        else
            super.get(name)
    }
}


class InlineSourcesPacman(pacman: Pacman,val sources: List<Source>) : Pacman(pacman) {

    val modules: Deferred<Map<String,Deferred<ModuleScope>>> = globalDefer {
        val result = mutableMapOf<String, Deferred<ModuleScope>>()
        for (source in sources) {
            // retrieve the module name and script for deferred execution:
            val (name, script) = Compiler.compilePackage(source)
            // scope is created used pacman's root scope:
            val scope = ModuleScope(this@InlineSourcesPacman, source.startPos, name)
            // we execute scripts in parallel which allow cross-imports to some extent:
            result[name] = globalDefer { script.execute(scope); scope }
        }
        result
    }

    override suspend fun createModuleScope(name: String): ModuleScope? =
        modules.await()[name]?.await()

}


