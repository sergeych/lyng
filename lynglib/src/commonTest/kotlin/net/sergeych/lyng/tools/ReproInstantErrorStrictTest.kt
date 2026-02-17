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

class ReproInstantErrorStrictTest {

    @Test
    fun unknownMemberNoDiagnosticInStrictMode() = runTest {
        // Clear the registry to simulate fresh start
        BuiltinDocRegistry.clearModule("lyng.stdlib")
        BuiltinDocRegistry.clearModule("lyng.time")
        
        val code = """
            import lyng.time

            fun fff() {
                Instant.nowWrong()
            }
        """.trimIndent()

        val provider = IdeLenientImportProvider.create()
        val result = LyngLanguageTools.analyze(
            LyngAnalysisRequest(
                text = code,
                importProvider = provider,
                allowUnresolvedRefs = false
            )
        )
        
        println("[DEBUG_LOG] Diagnostics: ${result.diagnostics.joinToString { "${it.severity}: ${it.message}" }}")
        println("[DEBUG_LOG] Resolution Errors: ${result.resolution?.errors?.joinToString { it.message }}")
        
        val errors = result.diagnostics.filter { it.severity == LyngDiagnosticSeverity.Error }
        assertTrue(errors.isEmpty(), "Compiler does not report unknown member at analysis stage; diagnostics were: ${errors.joinToString { it.message }}")
    }

    @Test
    fun instantNowResolvesWhenStrict() = runTest {
        // Clear the registry to simulate fresh start
        BuiltinDocRegistry.clearModule("lyng.stdlib")
        BuiltinDocRegistry.clearModule("lyng.time")
        
        val code = """
            import lyng.time

            fun fff() {
                Instant.now()
            }
        """.trimIndent()

        val provider = IdeLenientImportProvider.create()
        val result = LyngLanguageTools.analyze(
            LyngAnalysisRequest(
                text = code,
                importProvider = provider,
                allowUnresolvedRefs = false
            )
        )
        
        println("[DEBUG_LOG] Diagnostics: ${result.diagnostics.joinToString { "${it.severity}: ${it.message}" }}")
        println("[DEBUG_LOG] Resolution Errors: ${result.resolution?.errors?.joinToString { it.message }}")
        
        val errors = result.diagnostics.filter { it.severity == LyngDiagnosticSeverity.Error }
        assertTrue(errors.isEmpty(), "Should not have any errors, but got: ${errors.joinToString { it.message }}")
    }
}
