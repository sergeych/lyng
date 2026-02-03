/*
 * Copyright 2026 Sergey S. Chernov
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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.stdlib_included.rootLyng
import kotlin.test.Test
import kotlin.test.assertTrue

class StdlibExtensionWrapperCoverageTest {
    @Test
    fun stdlibExtensionWrappersPresent() = runTest {
        val src = rootLyng
        val classNames = LinkedHashSet<String>()
        val classRe = Regex(
            "^\\s*(?:(?:abstract|override|closed|private|protected|static|open|extern)\\s+)*(?:fun|val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\.",
            RegexOption.MULTILINE
        )
        classRe.findAll(src).forEach { m ->
            val name = m.groupValues.getOrNull(1)?.trim()
            if (!name.isNullOrEmpty()) classNames.add(name)
        }

        val scope = Script.newScope()
        val missing = mutableListOf<String>()
        for (className in classNames) {
            val members = BuiltinDocRegistry.extensionMemberNamesFor(className)
            for (member in members) {
                val callable = scope.get(extensionCallableName(className, member))
                val getter = scope.get(extensionPropertyGetterName(className, member))
                if (callable == null && getter == null) {
                    missing += "$className.$member"
                }
            }
        }

        assertTrue(missing.isEmpty(), "Missing stdlib extension wrappers: ${missing.sorted()}")
    }
}
