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

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.bridge.*
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjReal
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun rawDecimalExternBindingDoesNotBreakDecimalLiteralRendering() = runTest {
        val scope = Script.newScope()
        var x = BigDecimal.ZERO

        scope.eval(
            """
            import lyng.decimal
            extern var X: Decimal
            """.trimIndent()
        )

        scope.globalBinder().bindGlobalVarRaw(
            name = "X",
            get = { it.newDecimal(x) },
            set = { _, value ->
                x = when (value) {
                    is ObjInt -> BigDecimal.fromLong(value.value)
                    is ObjReal -> BigDecimal.fromDouble(value.value)
                    is ObjInstance -> value.data as BigDecimal
                    else -> error("unexpected value: $value")
                }
            }
        )

        scope.eval(
            """
            fun main() {
                assertEquals("42", 42.d.toStringExpanded())
            }

            main()
            """.trimIndent()
        )

        assertEquals(BigDecimal.ZERO, x)
    }

    @Test
    fun externDecimalDeclarationAloneDoesNotBreakDecimalLiteralRendering() = runTest {
        val scope = Script.newScope()

        scope.eval(
            """
            import lyng.decimal
            extern var X: Decimal

            fun main() {
                assertEquals("42", 42.d.toStringExpanded())
            }

            main()
            """.trimIndent()
        )
    }

    @Test
    fun parserKeeps42DotDAsIntDotIdentifierAfterExternDecimalDeclaration() = runTest {
        val tokens = parseLyng(
            Source(
                "test",
                """
                import lyng.decimal
                extern var X: Decimal

                fun main() {
                    42.d.toStringExpanded()
                }
                """.trimIndent()
            )
        )
        val tokenTexts = tokens.map { it.type to it.value }
        val needle = listOf(
            Token.Type.INT to "42",
            Token.Type.DOT to ".",
            Token.Type.ID to "d",
            Token.Type.DOT to ".",
            Token.Type.ID to "toStringExpanded",
        )
        val found = tokenTexts.windowed(needle.size).any { it == needle }
        assertTrue(found, tokenTexts.joinToString())
    }
}
