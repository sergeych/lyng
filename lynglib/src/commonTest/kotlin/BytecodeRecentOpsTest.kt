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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.*
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BytecodeRecentOpsTest {

    @Test
    fun listLiteralWithSpread() = runTest {
        eval(
            """
            val a = [1, 2, 3]
            val b = [0, ...a, 4]
            assertEquals(5, b.size)
            assertEquals(0, b[0])
            assertEquals(1, b[1])
            assertEquals(4, b[4])
            """.trimIndent()
        )
    }

    @Test
    fun valueFnRefViaClassOperator() = runTest {
        eval(
            """
            val c = 1::class
            assertEquals("Int", c.className)
            """.trimIndent()
        )
    }

    @Test
    fun implicitThisCompoundAssign() = runTest {
        eval(
            """
            class C {
                var x: Int = 1
                fun add(n: Int) { x += n }
                fun calc() { add(2); x }
            }
            val c = C()
            assertEquals(3, c.calc())
            """.trimIndent()
        )
    }

    @Test
    fun optionalCompoundAssignEvaluatesRhsOnce() = runTest {
        eval(
            """
            var count = 0
            fun inc() { count = count + 1; return 3 }
            class Box(var v)
            var b = Box(1)
            b?.v += inc()
            assertEquals(4, b.v)
            assertEquals(1, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexCompoundAssignEvaluatesRhsOnce() = runTest {
        eval(
            """
            var count = 0
            fun inc() { count = count + 1; return 2 }
            var a = [1, 2, 3]
            a?[1] += inc()
            assertEquals(4, a[1])
            assertEquals(1, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexIncDecSkipsOnNullReceiver() = runTest {
        eval(
            """
            var count = 0
            fun idx() { count = count + 1; return 1 }
            var a: List<Int>? = null
            val r = a?[idx()]++
            assertEquals(null, r)
            assertEquals(0, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexIncDecUpdatesOnNonNullReceiver() = runTest {
        eval(
            """
            var a = [1, 2, 3]
            val r = a?[1]++
            assertEquals(2, r)
            assertEquals(3, a[1])
            """.trimIndent()
        )
    }

    @Test
    fun intListIndexOpsUsePrimitiveBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                var a: List<Int> = [1, 2, 3]
                val s = a[1]
                a[1] += 3
                a[1]++
                a[1] = s
                a[1]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("GET_INDEX_INT"), disasm)
        assertTrue(disasm.contains("SET_INDEX_INT"), disasm)
        assertEquals(2, scope.eval("calc()").toInt())
    }

    @Test
    fun listFillIntIndexOpsUsePrimitiveBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                var a = List.fill(4) { 2 }
                val s = a[1]
                a[1] += 3
                a[1] = s
                a[1]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("GET_INDEX_INT"), disasm)
        assertTrue(disasm.contains("SET_INDEX_INT"), disasm)
        assertEquals(2, scope.eval("calc()").toInt())
    }

    @Test
    fun listFillConstantExpressionUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val xs = List.fill(5) { 2 }
                xs[0] + xs[4]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("LIST_NEW_INT"), disasm)
        assertFalse(disasm.contains("LIST_FILL_INT"), disasm)
        assertEquals(4, scope.eval("calc()").toInt())
    }

    @Test
    fun listFillCapturedExpressionUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val k = 3
                val xs = List.fill(5) { it * k }
                xs[0] + xs[4]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("LIST_NEW_INT"), disasm)
        assertFalse(disasm.contains("LIST_FILL_INT"), disasm)
        assertEquals(12, scope.eval("calc()").toInt())
    }

    @Test
    fun listFillIdentityUsesIotaBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val xs = List.fill(5) { it }
                xs[0] + xs[4]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("LIST_IOTA_INT"), disasm)
        assertEquals(4, scope.eval("calc()").toInt())
    }

    @Test
    fun directLambdaLiteralCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                { x -> x + 1 }(10)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("MAKE_LAMBDA_FN"), disasm)
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun directLambdaLiteralCallWithCaptureUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val k = 3
                { x -> x * k }(4)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("MAKE_LAMBDA_FN"), disasm)
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(12, scope.eval("calc()").toInt())
    }

    @Test
    fun localImmutableLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val f = { x -> x + 1 }
                f(10)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun localImmutableCapturedLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val k = 3
                val f = { x -> x * k }
                f(4)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(12, scope.eval("calc()").toInt())
    }

    @Test
    fun aliasedImmutableLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val f = { x -> x + 1 }
                val g = f
                g(10)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun topLevelImmutableLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            val f = { x -> x + 1 }
            fun calc() {
                f(10)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun topLevelAliasedImmutableLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            val f = { x -> x + 1 }
            val g = f
            fun calc() {
                g(10)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun topLevelCapturedLambdaCallUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            val k = 3
            val f = { x -> x * k }
            fun calc() {
                f(4)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(12, scope.eval("calc()").toInt())
    }

    @Test
    fun nestedInlineLambdaParamCallAvoidsCallSlot() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                { g -> g(10) }({ x -> x + 1 })
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_SLOT"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun letLiteralUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                10.let { it + 1 }
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun letAliasedLambdaUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val k = 3
                val f = { x -> x * k }
                4.let(f)
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(12, scope.eval("calc()").toInt())
    }

    @Test
    fun alsoLiteralUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                var acc = 0
                val result = 10.also { x ->
                    acc = x + 1
                }
                acc + result
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(21, scope.eval("calc()").toInt())
    }

    @Test
    fun optionalLetLiteralUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc(flag: Bool) {
                val x: Int? = if(flag) 10 else null
                x?.let { it + 1 }
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(11, scope.eval("calc(true)").toInt())
        assertEquals(ObjNull, scope.eval("calc(false)"))
    }

    @Test
    fun applyReceiverLambdaUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            class Box(value: Int)

            fun calc() {
                val box = Box(10).apply { value += 1 }
                box.value
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun runReceiverLambdaUsesInlineBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            class Box(value: Int)

            fun calc() {
                Box(10).run { value + 1 }
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(11, scope.eval("calc()").toInt())
    }

    @Test
    fun forEachUsesInlineLoopBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                [1, 2, 3, 4].forEach { it + 1 }
                5
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("ITER_PUSH"), disasm)
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(5, scope.eval("calc()").toInt())
    }

    @Test
    fun mapUsesInlineLoopBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val f = { x -> x * 2 }
                val xs = [1, 2, 3].map(f)
                xs[0] + xs[2]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("ITER_PUSH"), disasm)
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(8, scope.eval("calc()").toInt())
    }

    @Test
    fun filterUsesInlineLoopBytecode() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun calc() {
                val xs = [1, 2, 3, 4].filter { it % 2 == 0 }
                xs[0] + xs[1]
            }
            """.trimIndent()
        )
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("ITER_PUSH"), disasm)
        assertFalse(disasm.contains("CALL_DYNAMIC_MEMBER"), disasm)
        assertEquals(6, scope.eval("calc()").toInt())
    }

    @Test
    fun optionalIndexPreIncSkipsOnNullReceiver() = runTest {
        eval(
            """
            var count = 0
            fun idx() { count = count + 1; return 1 }
            var a: List<Int>? = null
            val r = ++a?[idx()]
            assertEquals(null, r)
            assertEquals(0, count)
            """.trimIndent()
        )
    }

    @Test
    fun optionalClassScopeIncDec() = runTest {
        eval(
            """
            class C { static var x = 1 }
            val r = C?.x++
            assertEquals(1, r)
            assertEquals(2, C.x)
            """.trimIndent()
        )
    }

    @Test
    fun classScopeIfNullAssign() = runTest {
        eval(
            """
            class C { static var x: Object? = null }
            C.x ?= 7
            assertEquals(7, C.x)
            C.x ?= 9
            assertEquals(7, C.x)
            """.trimIndent()
        )
    }

    @Test
    fun callablePropertyCall() = runTest {
        eval(
            """
            class C { var f = { x -> x + 1 } }
            val c = C()
            val r = (c.f)(2)
            assertEquals(3, r)
            """.trimIndent()
        )
    }

    @Test
    fun lambdaCapturesLocalByReference() = runTest {
        eval(
            """
            fun make() {
                var base = 3
                val f = { x -> x + base }
                base = 7
                return f(1)
            }
            assertEquals(8, make())
            """.trimIndent()
        )
    }

    @Test
    fun lambdaCapturesDelegatedLocal() = runTest {
        eval(
            """
            class BoxDelegate(var v) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = v
                override fun setValue(thisRef: Object, name: String, value: Object) { v = value }
            }
            fun make() {
                var x by BoxDelegate(1)
                val f = { y -> x += y; return x }
                return f(2)
            }
            assertEquals(3, make())
            """.trimIndent()
        )
    }

    @Test
    fun delegatedMemberAccessAndCall() = runTest {
        eval(
            """
            class ConstDelegate(val v) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = v
            }
            class ActionDelegate : Delegate {
                override fun invoke(thisRef: Object, name: String, args...) {
                    val list: List = args as List
                    "Called %s with %d args: %s"(name, list.size, list.toString())
                }
            }
            class C {
                val a by ConstDelegate(7)
                fun greet by ActionDelegate()
            }
            val c = C()
            assertEquals(7, c.a)
            assertEquals("Called greet with 2 args: [hi,world]", c.greet("hi", "world"))
            """.trimIndent()
        )
    }

    @Test
    fun delegatedLocalAssignAndIncDec() = runTest {
        eval(
            """
            class BoxDelegate(var v) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = v
                override fun setValue(thisRef: Object, name: String, value: Object) { v = value }
            }
            fun calc() {
                var x by BoxDelegate(1)
                x += 2
                x++
                return x
            }
            assertEquals(4, calc())
            """.trimIndent()
        )
    }

    @Test
    fun delegatedLocalDisasmUsesDelegateOps() = runTest {
        val script = """
            class BoxDelegate(var v) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = v
                override fun setValue(thisRef: Object, name: String, value: Object) { v = value }
            }
            fun calc() {
                var x by BoxDelegate(1)
                x += 2
                x++
                return x
            }
        """.trimIndent()
        val compiled = Compiler.compile(script.toSource(), Script.defaultImportManager)
        val scope = Script.defaultImportManager.newModuleAt(Pos.builtIn)
        compiled.execute(scope)
        val disasm = scope.disassembleSymbol("calc")
        assertTrue(disasm.contains("DELEGATED_GET_LOCAL"), disasm)
        assertTrue(disasm.contains("DELEGATED_SET_LOCAL"), disasm)
        assertTrue(disasm.contains("DECL_DELEGATED"), disasm)
    }

    @Test
    fun unionMemberDispatchSubtype() = runTest {
        eval(
            """
            class A { fun who() = "A" }
            class B : A { override fun who() = "B" }
            fun pick(x: A | B) { x.who() }
            assertEquals("B", pick(B()))
            """.trimIndent()
        )
    }

    @Test
    fun staticMemberDeclNopStatement() = runTest {
        eval(
            """
            class C {
                static fun ping() { 7 }
            }
            assertEquals(7, C.ping())
            """.trimIndent()
        )
    }

    @Test
    fun unionMemberDispatchMismatch() = runTest {
        val err = assertFailsWith<ExecutionError> {
            eval(
                """
                class A { fun who() = "A" }
                class B { fun who() = "B" }
                val x: A | B = 1
                x.who()
                """.trimIndent()
            )
        }
        assertTrue(err.message?.contains("value is not A | B") == true)
    }

    @Test
    fun objectReceiverMemberError() = runTest {
        val failed = try {
            eval("fun bad(x) { x.missing() }")
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun unionMissingMemberError() = runTest {
        val failed = try {
            eval(
                """
                class A { fun who() = "A" }
                class B { fun other() = "B" }
                fun pick(x: A | B) { x.who() }
                """.trimIndent()
            )
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun qualifiedThisValueRef() = runTest {
        eval(
            """
            class T(val v) {
                fun get() {
                    this@T.v
                }
            }
            assertEquals(7, T(7).get())
            """.trimIndent()
        )
    }

    @Test
    fun fastLocalVarRefRead() = runTest {
        val code = """
            fun addOne(x) {
                val y = x + 1
                y
            }
            addOne(1)
        """.trimIndent()
        val script = Compiler.compileWithResolution(
            Source("<fast-local>", code),
            Script.defaultImportManager,
            useFastLocalRefs = true
        )
        val result = script.execute(Script.defaultImportManager.newStdScope())
        assertEquals(2, result.toInt())
    }

}
