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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.bridge.bind
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.bridge.data
import net.sergeych.lyng.bridge.bindGlobalVar
import net.sergeych.lyng.bridge.globalBinder
import net.sergeych.lyng.obj.ObjFalse
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalPropertyCaptureRegressionTest {
    @Test
    fun externGlobalVarAssignmentInsideFunctionShouldCallBoundSetter() = runTest {
        val scope = Script.newScope()
        var x = 1.0

        scope.eval(
            """
            extern var X: Real

            fun main() {
                X = X + 1.0
            }
            """.trimIndent()
        )

        scope.globalBinder().bindGlobalVar(
            name = "X",
            get = { x },
            set = { x = it }
        )

        scope.eval("main()")

        assertEquals(2.0, x, "bound extern var should stay live inside function bodies")
    }

    @Test
    fun externGlobalVarShouldStayLiveWhenScriptRunsInChildScope() = runTest {
        val base = Script.newScope() as ModuleScope
        var x = 1.0

        base.eval("extern var X: Real")
        base.globalBinder().bindGlobalVar(
            name = "X",
            get = { x },
            set = { x = it }
        )

        val child = base.createChildScope()
        child.eval(
            Source(
                "child-scope-probe",
                """
                fun main() {
                    X = X + 1.0
                }
                """.trimIndent()
            )
        )

        val mainRecord = child["main"]
        check(mainRecord?.type == ObjRecord.Type.Fun)
        child.eval("main()")

        assertEquals(2.0, x, "bound extern var should stay live in child-scope execution")
    }

    @Test
    fun externGlobalVarShouldStayLiveAfterExternClassPropertyBranchInChildScope() = runTest {
        val base = Script.newScope() as ModuleScope
        var x = 3.0

        base.eval(
            """
            extern var X: Real

            class ChoiceInputResult {
                extern val isSkip: Bool
            }

            extern fun requestChoice(): ChoiceInputResult
            """.trimIndent()
        )

        base.bind("ChoiceInputResult") {
            addVal("isSkip") {
                if (thisObjData<ChoicePayload>().isSkip) ObjTrue else ObjFalse
            }
        }

        base.globalBinder().bindGlobalVar(
            name = "X",
            get = { x },
            set = { x = it }
        )

        base.globalBinder().bindGlobalFunRaw("requestChoice") { _, _ ->
            val instance = base.requireClass("ChoiceInputResult").callOn(base.createChildScope()) as ObjInstance
            instance.data = ChoicePayload(isSkip = true)
            instance
        }

        val child = base.createChildScope()
        child.eval(
            """
            fun main() {
                val c: ChoiceInputResult = requestChoice()
                if (c.isSkip) {
                    X = 77.0
                }
            }
            """.trimIndent()
        )

        child.eval("main()")

        assertEquals(77.0, x, "bound extern var should stay live after extern class property branch in child scope")
    }
}

private data class ChoicePayload(
    val isSkip: Boolean,
)

@Suppress("UNCHECKED_CAST")
private fun <T> ScopeFacade.thisObjData(): T {
    val instance = thisObj as? ObjInstance ?: raiseClassCastError("Expected result object instance")
    return instance.data as? T ?: raiseIllegalState("Bridge payload is not initialized")
}
