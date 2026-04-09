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

package net.sergeych.lyng.idea.util

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.console.createConsoleModule
import net.sergeych.lyng.io.fs.createFs
import net.sergeych.lyng.io.http.createHttpModule
import net.sergeych.lyng.io.net.createNetModule
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyng.io.ws.createWsModule
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.pacman.ImportProvider
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy
import net.sergeych.lyngio.fs.security.PermitAllAccessPolicy
import net.sergeych.lyngio.http.security.PermitAllHttpAccessPolicy
import net.sergeych.lyngio.net.security.PermitAllNetAccessPolicy
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import net.sergeych.lyngio.ws.security.PermitAllWsAccessPolicy

/**
 * IDE import provider that knows about optional LyngIO modules used by editor analysis.
 *
 * The default import manager only exposes core modules; editor features need the pluggable
 * `lyng.io.*` packages available as well so imported symbols resolve without false errors.
 */
class LyngIdeaImportProvider private constructor(root: Scope) : ImportProvider(root) {
    override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope {
        return try {
            baseImportManager.createModuleScope(pos, packageName)
        } catch (_: Throwable) {
            ModuleScope(this, pos, packageName)
        }
    }

    companion object {
        private val baseImportManager: ImportManager by lazy {
            Script.defaultImportManager.copy().apply {
                createFs(PermitAllAccessPolicy, this)
                createConsoleModule(PermitAllConsoleAccessPolicy, this)
                createHttpModule(PermitAllHttpAccessPolicy, this)
                createWsModule(PermitAllWsAccessPolicy, this)
                createNetModule(PermitAllNetAccessPolicy, this)
                createProcessModule(PermitAllProcessAccessPolicy, this)
            }
        }

        fun create(): LyngIdeaImportProvider = LyngIdeaImportProvider(baseImportManager.rootScope)
    }
}
