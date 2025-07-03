package net.sergeych.lyng

import kotlinx.coroutines.CompletableDeferred
import net.sergeych.mp_tools.globalDefer
import net.sergeych.mp_tools.globalLaunch

/**
 * Naive source-based pacman that compiles all sources first, before first import could be resolved.
 * It supports imports between [sources] but does not resolve nor detect cyclic imports which
 * are not supported.
 */
class InlineSourcesPacman(pacman: Pacman, val sources: List<Source>) : Pacman(pacman) {

    data class Entry(val source: Source, val deferredModule: CompletableDeferred<ModuleScope> = CompletableDeferred())


    private val modules = run {
        val result = mutableMapOf<String, Entry>()
        for (source in sources) {
            val name = source.extractPackageName()
            result[name] = Entry(source)
        }
        val inner = InitMan()

        for ((name, entry) in result) {
            globalLaunch {
                val scope = ModuleScope(inner, entry.source.startPos, name)
                Compiler.compile(entry.source, inner).execute(scope)
                entry.deferredModule.complete(scope)
            }
        }
        result
    }

    inner class InitMan : Pacman(parent) {
        override suspend fun createModuleScope(name: String): ModuleScope? = modules[name]?.deferredModule?.await()
    }

    private val readyModules by lazy {
        globalDefer {
            modules.entries.map { it.key to it.value.deferredModule.await() }.toMap()
        }
    }


    override suspend fun createModuleScope(name: String): ModuleScope? =
        readyModules.await()[name]
}