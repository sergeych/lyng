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
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.bridge.bindGlobalVar
import net.sergeych.lyng.bridge.globalBinder
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
}
