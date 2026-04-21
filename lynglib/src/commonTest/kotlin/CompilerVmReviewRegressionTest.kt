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
import net.sergeych.lyng.Arguments
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.toInt
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerVmReviewRegressionTest {

    @Test
    fun missingModuleCaptureFailsFastInsteadOfBecomingUnset() = runTest {
        val manager = ImportManager()
        manager.addTextPackages(
            """
            package foo

            val answer = 42
            """.trimIndent()
        )
        val script = Compiler.compile(
            Source(
                "<missing-module-capture>",
                """
                import foo
                fun make() = { answer }
                make()
                """.trimIndent()
            ),
            manager
        )

        val prepared = manager.newModule()
        script.importInto(prepared)
        val preparedLambda = script.execute(prepared)
        assertEquals(42, prepared.asFacade().call(preparedLambda).toInt())

        val rawModule = manager.newModule()
        val hostScope = Scope(parent = rawModule, thisObj = ObjString("receiver"))

        val lambda = script.execute(hostScope)
        val ex = assertFailsWith<ScriptError> {
            hostScope.asFacade().call(lambda)
        }
        assertContains(ex.errorMessage, "module binding 'answer'")
    }

    @Test
    fun facadeCallUsesPreparedLambdaWithArgs() = runTest {
        val lambda = Compiler.compile(
            Source(
                "<facade-call-lambda>",
                """
                val base = 2
                { x -> x + base }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )

        val scope = Script.newScope()
        val callable = lambda.execute(scope)
        assertEquals(42, scope.asFacade().call(callable, Arguments(ObjInt.of(40))).toInt())
    }

    @Test
    fun subjectlessWhenReportsScriptError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            Compiler.compile(
                Source(
                    "<when-without-subject>",
                    """
                    when {
                        true -> 1
                    }
                    """.trimIndent()
                ),
                Script.defaultImportManager
            )
        }
        assertContains(ex.errorMessage, "when without subject")
    }
}
