package net.sergeych.lyng.pacman

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.*

/**
 * The import provider that imports sources available in memory.
 */
class InlineSourcesImportProvider(val sources: List<Source>,
                                  rootScope: ModuleScope = ModuleScope(emptyAllowAll, Source.builtIn),
                                  securityManager: SecurityManager = SecurityManager.allowAll
) : ImportProvider(rootScope, securityManager) {

    private class Entry(
        val source: Source,
        var scope: ModuleScope? = null,
    )

    private val inner = InitMan()

    private val modules = run {
        val result = mutableMapOf<String, Entry>()
        for (source in sources) {
            val name = source.extractPackageName()
            result[name] = Entry(source)
        }
        result
    }

    private var access = Mutex()

    /**
     * Inner provider does not lock [access], the only difference; it is meant to be used
     * exclusively by the coroutine that starts actual import chain
     */
    private inner class InitMan : ImportProvider() {
        override suspend fun createModuleScope(pos: Pos,packageName: String): ModuleScope {
            return doImport(packageName, pos)
        }
    }

    /**
     * External interface, thread-safe. Can suspend until actual import is done. implements caching.
     */
    override suspend fun createModuleScope(pos: Pos,packageName: String): ModuleScope =
        access.withLock {
            doImport(packageName, pos)
        }

    /**
     * Perform actual import or return ready scope. __It must only be called when
     * [access] is locked__, e.g. only internally
     */
    private suspend fun doImport(packageName: String, pos: Pos): ModuleScope {
        modules[packageName]?.scope?.let { return it }
        val entry = modules[packageName] ?: throw ImportException(pos, "Unknown package $packageName")
        return ModuleScope(inner, pos, packageName).apply {
            entry.scope = this
            eval(entry.source)
        }
    }
}