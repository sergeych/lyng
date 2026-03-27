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
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.toInt
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScriptImportPreparationTest {

    @Test
    fun scriptImportIntoExplicitlyPreparesExistingScope() = runTest {
        val manager = ImportManager()
        manager.addTextPackages(
            """
            package foo

            val answer = 42
            """.trimIndent()
        )
        val script = Compiler.compile(
            Source(
                "<prepare-scope>",
                """
                import foo
                answer
                """.trimIndent()
            ),
            manager
        )
        val scope = manager.newModule()

        assertNull(scope["answer"])

        script.importInto(scope)

        val record = assertNotNull(scope["answer"])
        assertEquals(42, scope.resolve(record, "answer").toInt())
    }

    @Test
    fun scriptInstantiateModuleUsesSeedScopeImportProvider() = runTest {
        val manager = ImportManager()
        manager.addTextPackages(
            """
            package foo

            val answer = 42
            """.trimIndent()
        )
        val script = Compiler.compile(
            Source(
                "<instantiate-module>",
                """
                import foo
                answer
                """.trimIndent()
            ),
            manager
        )
        val seedScope = manager.newModule()

        val module = script.instantiateModule(seedScope)

        val record = assertNotNull(module["answer"])
        assertEquals(42, module.resolve(record, "answer").toInt())
    }
}
