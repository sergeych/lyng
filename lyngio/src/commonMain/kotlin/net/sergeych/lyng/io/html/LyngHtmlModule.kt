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

package net.sergeych.lyng.io.html

import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Source
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyngio.stdlib_included.htmlLyng

private const val HTML_MODULE_NAME = "lyng.io.html"

fun createHtmlModule(scope: Scope): Boolean = createHtmlModule(scope.importManager)

fun createHtml(scope: Scope): Boolean = createHtmlModule(scope)

fun createHtmlModule(manager: ImportManager): Boolean {
    if (manager.packageNames.contains(HTML_MODULE_NAME)) return false
    manager.addPackage(HTML_MODULE_NAME) { module ->
        buildHtmlModule(module)
    }
    return true
}

fun createHtml(manager: ImportManager): Boolean = createHtmlModule(manager)

private suspend fun buildHtmlModule(module: ModuleScope) {
    module.eval(Source(HTML_MODULE_NAME, htmlLyng))
}
