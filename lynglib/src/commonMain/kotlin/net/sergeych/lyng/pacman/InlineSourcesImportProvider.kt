package net.sergeych.lyng.pacman

import kotlinx.coroutines.CompletableDeferred
import net.sergeych.lyng.*
import net.sergeych.mp_tools.globalLaunch

/**
 * The sample import provider that imports sources available in memory.
 * on construction time.
 *
 * Actually it is left here only as a demo.
 */
class InlineSourcesImportProvider(sources: List<Source>,
                                  rootScope: ModuleScope = Script.defaultImportManager.newModule(),
                                  securityManager: SecurityManager = SecurityManager.allowAll
) : ImportProvider(rootScope) {

    private val manager = ImportManager(rootScope, securityManager)

    private val readyManager = CompletableDeferred<ImportManager>()

    /**
     * This implementation only
     */
    override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope {
        return readyManager.await().createModuleScope(pos, packageName)
    }

    init {
        globalLaunch {
            manager.addSourcePackages(*sources.toTypedArray())
            readyManager.complete(manager)
        }
    }
}