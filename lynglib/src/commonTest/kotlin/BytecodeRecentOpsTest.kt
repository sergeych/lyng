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
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Script
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.Source
import net.sergeych.lyng.eval
import net.sergeych.lyng.toSource
import net.sergeych.lyng.bytecode.CmdDisassembler
import net.sergeych.lyng.bytecode.CmdFunction
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun moduleDeclsAvoidCallableCallSlots() = runTest {
        val script = """
            class A {}
            fun f() { 1 }
            enum E { one }
        """.trimIndent()
        val compiled = Compiler.compile(script.toSource(), Script.defaultImportManager)
        val field = Script::class.java.getDeclaredField("moduleBytecode")
        field.isAccessible = true
        val moduleFn = field.get(compiled) as? CmdFunction
        assertNotNull(moduleFn, "module bytecode missing")
        val disasm = CmdDisassembler.disassemble(moduleFn)
        assertTrue(!disasm.contains("CALL_SLOT"), disasm)
        assertTrue(!disasm.contains("Callable@"), disasm)
        assertTrue(disasm.contains("DECL_CLASS"), disasm)
        assertTrue(disasm.contains("DECL_FUNCTION"), disasm)
        assertTrue(disasm.contains("DECL_ENUM"), disasm)
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
            useBytecodeStatements = true,
            useFastLocalRefs = true
        )
        val result = script.execute(Script.defaultImportManager.newStdScope())
        assertEquals(2, result.toInt())
    }
}
