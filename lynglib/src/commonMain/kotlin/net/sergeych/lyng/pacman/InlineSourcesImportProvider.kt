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