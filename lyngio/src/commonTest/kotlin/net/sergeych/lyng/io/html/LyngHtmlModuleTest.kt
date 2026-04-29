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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyngHtmlModuleTest {

    @Test
    fun testModuleRegistrationIsIdempotent() = runTest {
        val importManager = ImportManager()
        assertTrue(createHtmlModule(importManager))
        assertFalse(createHtmlModule(importManager))
    }

    @Test
    fun testModuleCanBeImported() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-test>",
                """
                import lyng.io.html
                42
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals("42", result.inspect(scope))
    }
}
