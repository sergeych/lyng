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


package net.sergeych.lyng.tools

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.miniast.BuiltinDocRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

class ReproInstantErrorTest {

    @Test
    fun testInstantResolutionOnFirstPass() = runTest {
        // Clear the registry to simulate fresh start
        BuiltinDocRegistry.clearModule("lyng.stdlib")
        BuiltinDocRegistry.clearModule("lyng.time")
        
        val code = """
            import lyng.time

            fun fff() {
                Instant.now()
            }
        """.trimIndent()

        // Analyze without any prior "touches"
        val result = LyngLanguageTools.analyze(code)
        
        // Check if there are any errors related to Instant
        val instantErrors = result.diagnostics.filter { it.message.contains("Instant") }
        
        assertTrue(instantErrors.isEmpty(), "Should not have Instant-related errors on first pass, but got: ${instantErrors.joinToString { it.message }}")
    }
}
