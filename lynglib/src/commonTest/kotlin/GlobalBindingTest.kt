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
import net.sergeych.lyng.bridge.bindGlobalFun1
import net.sergeych.lyng.bridge.bindGlobalFun3
import net.sergeych.lyng.bridge.bindGlobalVar
import net.sergeych.lyng.bridge.globalBinder
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertTrue

class GlobalBindingTest {
    @Test
    fun testPackageGlobalFunAndVarBinding() = runTest {
        val im = Script.defaultImportManager.copy()
        var prop = "initial"
        im.addPackage("bridge.globals") { module ->
            module.eval(
                """
                extern fun globalFun(v: Int): Int
                extern fun join3(a: String, b: String, c: String): String
                extern var globalProp: String
                extern val answer: Int
                """.trimIndent()
            )
            val binder = module.globalBinder()
            binder.bindGlobalFun1<Int>("globalFun") { v ->
                ObjInt.of((v + 10).toLong())
            }
            binder.bindGlobalFun3<String, String, String>("join3") { a, b, c ->
                ObjString(a + b + c)
            }
            binder.bindGlobalVar(
                name = "globalProp",
                get = { prop },
                set = { prop = it }
            )
            binder.bindGlobalVar(
                name = "answer",
                get = { 42 }
            )
        }

        val scope = im.newStdScope()
        scope.eval(
            """
            import bridge.globals
            assertEquals(15, globalFun(5))
            assertEquals("abc", join3("a", "b", "c"))
            assertEquals("initial", globalProp)
            globalProp = "changed"
            assertEquals("changed", globalProp)
            assertEquals(42, answer)
            """.trimIndent()
        )
    }

    @Test
    fun testPackageGlobalRawAndArgReaderBinding() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("bridge.raw") { module ->
            module.eval(
                """
                extern fun sum3(a: Int, b: Int, c: Int): Int
                extern fun echoRaw(x: Int): Int
                """.trimIndent()
            )
            val binder = module.globalBinder()
            binder.bindGlobalFun("sum3") {
                requireExactCount(3)
                ObjInt.of((int(0) + int(1) + int(2)).toLong())
            }
            binder.bindGlobalFunRaw("echoRaw") { _, args ->
                args.firstAndOnly()
            }
        }
        val scope = im.newStdScope()
        scope.eval(
            """
            import bridge.raw
            assertEquals(6, sum3(1, 2, 3))
            assertEquals(77, echoRaw(77))
            """.trimIndent()
        )
    }

    @Test
    fun testGlobalVarExternCompatibilityChecks() = runTest {
        val im = Script.defaultImportManager.copy()
        im.addPackage("bridge.compat") { module ->
            module.eval(
                """
                extern var needsSetter: String
                extern val noSetterAllowed: String
                """.trimIndent()
            )
            val binder = module.globalBinder()
            val missingSetter = try {
                binder.bindGlobalVar(
                    name = "needsSetter",
                    get = { "x" }
                )
                false
            } catch (_: ScriptError) {
                true
            }
            val readonlySetter = try {
                binder.bindGlobalVar(
                    name = "noSetterAllowed",
                    get = { "x" },
                    set = { _ -> }
                )
                false
            } catch (_: ScriptError) {
                true
            }
            assertTrue(missingSetter)
            assertTrue(readonlySetter)
        }
    }
}
