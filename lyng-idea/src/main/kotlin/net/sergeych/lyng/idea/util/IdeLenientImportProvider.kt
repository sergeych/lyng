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
package net.sergeych.lyng.idea.util

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.pacman.ImportProvider

/**
 * Import provider for IDE background features that never throws on missing modules.
 * It allows all imports and returns an empty [ModuleScope] for unknown packages so
 * the compiler can still build MiniAst for Quick Docs / highlighting.
 */
class IdeLenientImportProvider private constructor(root: Scope) : ImportProvider(root) {
    override suspend fun createModuleScope(pos: Pos, packageName: String): ModuleScope = ModuleScope(this, pos, packageName)

    companion object {
        /** Create a provider based on the default manager's root scope. */
        fun create(): IdeLenientImportProvider = IdeLenientImportProvider(Script.defaultImportManager.rootScope)
    }
}
