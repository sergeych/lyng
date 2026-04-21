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
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.Statement
import net.sergeych.lyng.asFacade
import net.sergeych.lyng.obj.ObjDynamic
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
    fun genericInvokeHelpersUsePreparedLambdaEntryPoints() = runTest {
        val unaryLambda = Compiler.compile(
            Source(
                "<generic-invoke-unary>",
                """
                val delta = 2
                { x -> x + delta }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )
        val nullaryLambda = Compiler.compile(
            Source(
                "<generic-invoke-nullary>",
                """
                val base = 7
                { base }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )

        val unaryScope = Script.newScope()
        val nullaryScope = Script.newScope()
        val unaryCallable = unaryLambda.execute(unaryScope)
        val nullaryCallable = nullaryLambda.execute(nullaryScope)

        assertEquals(42, unaryCallable.invoke(unaryScope, ObjString("receiver"), ObjInt.of(40)).toInt())
        assertEquals(7, nullaryCallable.invoke(nullaryScope, ObjString("receiver")).toInt())
        assertEquals(
            42,
            unaryCallable.invoke(
                unaryScope,
                Pos(Source("<generic-invoke-pos>", ""), 0, 0),
                ObjString("receiver"),
                Arguments(ObjInt.of(40))
            ).toInt()
        )
    }

    @Test
    fun statementCallUsesPreparedLambdaFastPath() = runTest {
        val unaryLambda = Compiler.compile(
            Source(
                "<statement-call-unary>",
                """
                val delta = 2
                { x -> x + delta }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )

        val scope = Script.newScope()
        val callable = unaryLambda.execute(scope) as Statement

        assertEquals(42, callable.call(scope, ObjInt.of(40)).toInt())
    }

    @Test
    fun preparedLambdaKeepsImmutableModuleCaptureAcrossOtherScriptsInSameScope() = runTest {
        val unaryLambda = Compiler.compile(
            Source(
                "<cross-script-capture-unary>",
                """
                val delta = 2
                { x -> x + delta }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )
        val unrelatedScript = Compiler.compile(
            Source(
                "<cross-script-capture-unrelated>",
                """
                val base = 7
                base
                """.trimIndent()
            ),
            Script.defaultImportManager
        )

        val scope = Script.newScope()
        val callable = unaryLambda.execute(scope) as Statement
        unrelatedScript.execute(scope)

        assertEquals(42, callable.call(scope, ObjInt.of(40)).toInt())
        assertEquals(42, scope.asFacade().call(callable, Arguments(ObjInt.of(40))).toInt())
    }

    @Test
    fun dynamicCallbacksUsePreparedLambdaFastPath() = runTest {
        val script = Compiler.compile(
            Source(
                "<dynamic-fast-callbacks>",
                """
                var seen = ""
                dynamic {
                    get { name -> name + seen }
                    set { name, value -> seen = name + "=" + value }
                }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )

        val scope = Script.newScope()
        val dynamic = script.execute(scope) as ObjDynamic

        assertEquals("foo", (dynamic.readField(scope, "foo").value as ObjString).value)
        dynamic.writeField(scope, "foo", ObjInt.of(7))
        assertEquals("barfoo=7", (dynamic.getAt(scope, ObjString("bar")) as ObjString).value)
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
