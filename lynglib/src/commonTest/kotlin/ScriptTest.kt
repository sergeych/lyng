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


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import net.sergeych.lyng.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.InlineSourcesImportProvider
import net.sergeych.mp_tools.globalDefer
import net.sergeych.tools.bm
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
class ScriptTest {
    @Test
    fun testVersion() {
        println("--------------------------------------------")
        println("version = ${LyngVersion}")
    }

    @Test
    fun testClosureSeesCallerLocalsInLaunch() = runTest {
        val scope = Script.newScope()
        val res = scope.eval(
            """
            var counter = 0
            val d = launch {
                val c = counter
                delay(1)
                counter = c + 1
            }
            d.await()
            counter
            """.trimIndent()
        )
        assertEquals(1L, (res as ObjInt).value)
    }

    @Test
    fun testClosureResolvesGlobalsInLaunch() = runTest {
        val scope = Script.newScope()
        val res = scope.eval(
            """
            val d = launch {
                delay(1)
                yield()
            }
            d.await()
            42
            """.trimIndent()
        )
        assertEquals(42L, (res as ObjInt).value)
    }

    @Test
    fun testClosureSeesModulePseudoSymbol() = runTest {
        val scope = Script.newScope()
        val res = scope.eval(
            """
            val s = { __PACKAGE__ }
            s()
            """.trimIndent()
        )
        // __PACKAGE__ is a string; just ensure it's a string and non-empty
        assertTrue(res is ObjString && res.value.isNotEmpty())
    }

    @Test
    fun testNoInfiniteRecursionOnUnknownInNestedClosure() = runTest {
        val scope = Script.newScope()
        withTimeout(1.seconds) {
            // Access an unknown symbol inside nested closures; should throw quickly, not hang
            try {
                scope.eval(
                    """
                    val f = { { unknown_symbol_just_for_test } }
                    f()()
                    """.trimIndent()
                )
                fail("Expected exception not thrown")
            } catch (_: ExecutionError) {
                // ok
            } catch (_: ScriptError) {
                // ok
            }
        }
    }

    // --- Helpers to test iterator cancellation semantics ---
    class ObjTestIterable : Obj() {

        var cancelCount: Int = 0

        override val objClass: ObjClass = type

        companion object {
            val type = ObjClass("TestIterable", ObjIterable).apply {
                addFn("iterator") {
                    ObjTestIterator(thisAs<ObjTestIterable>())
                }
                addFn("cancelCount") { thisAs<ObjTestIterable>().cancelCount.toObj() }
            }
        }
    }

    class ObjTestIterator(private val owner: ObjTestIterable) : Obj() {
        override val objClass: ObjClass = type
        private var i = 0

        private fun hasNext(): Boolean = i < 5
        private fun next(): Obj = ObjInt((++i).toLong())
        private fun cancelIteration() {
            owner.cancelCount += 1
        }

        companion object {
            val type = ObjClass("TestIterator", ObjIterator).apply {
                addFn("hasNext") { thisAs<ObjTestIterator>().hasNext().toObj() }
                addFn("next") { thisAs<ObjTestIterator>().next() }
                addFn("cancelIteration") {
                    thisAs<ObjTestIterator>().cancelIteration()
                    ObjVoid
                }
            }
        }
    }

    @Test
    fun testForLoopDoesNotCancelOnNaturalCompletion() = runTest {
        val scope = Script.newScope()
        val ti = ObjTestIterable()
        scope.addConst("ti", ti)
        scope.eval(
            """
                var s = 0
                for( i in ti ) {
                    s += i
                }
                s
            """.trimIndent()
        )
        assertEquals(0, ti.cancelCount)
    }

    @Test
    fun testForLoopCancelsOnBreak() = runTest {
        val scope = Script.newScope()
        val ti = ObjTestIterable()
        scope.addConst("ti", ti)
        scope.eval(
            """
                for( i in ti ) {
                    break
                }
            """.trimIndent()
        )
        assertEquals(1, ti.cancelCount)
    }

    @Test
    fun testForLoopCancelsOnException() = runTest {
        val scope = Script.newScope()
        val ti = ObjTestIterable()
        scope.addConst("ti", ti)
        try {
            scope.eval(
                """
                    for( i in ti ) {
                        throw "boom"
                    }
                """.trimIndent()
            )
            fail("Exception expected")
        } catch (_: Exception) {
            // ignore
        }
        assertEquals(1, ti.cancelCount)
    }

    @Test
    fun parseNewlines() {
        fun check(expected: String, type: Token.Type, row: Int, col: Int, src: String, offset: Int = 0) {
            val source = src.toSource()
            assertEquals(
                Token(expected, source.posAt(row, col), type),
                parseLyng(source)[offset]
            )
        }
        check("1", Token.Type.INT, 0, 0, "1 + x\n2", 0)
        check("+", Token.Type.PLUS, 0, 2, "1 + x\n2", 1)
        check("x", Token.Type.ID, 0, 4, "1 + x\n2", 2)
        check("\n", Token.Type.NEWLINE, 0, 5, "1 + x\n2", 3)
//        check("2", Token.Type.INT, 1, 0, "1 + x\n2", 4)
//        check("", Token.Type.EOF, 1, 0, "1 + x\n2", 5)

    }

    @Test
    fun parseNumbersTest() {
        fun check(expected: String, type: Token.Type, row: Int, col: Int, src: String, offset: Int = 0) {
            val source = src.toSource()
            assertEquals(
                Token(expected, source.posAt(row, col), type),
                parseLyng(source)[offset]
            )
        }
        check("1", Token.Type.INT, 0, 0, "1")
        check("7", Token.Type.INT, 0, 0, "7")
        check("17", Token.Type.INT, 0, 0, "17")
        check("17", Token.Type.INT, 0, 0, "17.")
        check(".", Token.Type.DOT, 0, 2, "17.", 1)

        // decimals
        check("17.2", Token.Type.REAL, 0, 0, "17.2")
        check("17.2", Token.Type.REAL, 0, 0, "17.2")
        check("17.2", Token.Type.REAL, 0, 0, "17.2 ")
        check("17.2", Token.Type.REAL, 0, 1, " 17.2")
        check("17.2", Token.Type.REAL, 0, 2, "  17.2  ")
        check("17.2e0", Token.Type.REAL, 0, 0, "17.2e0")
        check("17.2e-22", Token.Type.REAL, 0, 0, "17.2e-22")
        check("17.2e22", Token.Type.REAL, 0, 0, "17.2e+22")
        check("17.2e22", Token.Type.REAL, 0, 0, "17.2E+22")
        check("17.2e22", Token.Type.REAL, 0, 0, "17.2E22")
        check("17.2e-22", Token.Type.REAL, 0, 0, "17.2E-22")
        check("17.2e-22", Token.Type.REAL, 0, 0, "17.2E-22")
        check("1e-22", Token.Type.REAL, 0, 0, "1E-22")

        // hex
        check("1", Token.Type.HEX, 0, 0, "0x1")
        check("12", Token.Type.HEX, 0, 0, "0x12")
        check("12abcdef", Token.Type.HEX, 0, 0, "0x12abcdef.gh")
        check(".", Token.Type.DOT, 0, 10, "0x12abcdef.gh", 1)
        check("gh", Token.Type.ID, 0, 11, "0x12abcdef.gh", 2)

        check("5", Token.Type.INT, 0, 0, "5 6")
        check("6", Token.Type.INT, 0, 2, "5 6", 1)

    }

    @Test
    fun parseRangeTest() {
        var tt = parseLyng("5 .. 4".toSource())

        assertEquals(Token.Type.INT, tt[0].type)
        assertEquals(Token.Type.DOTDOT, tt[1].type)
        assertEquals(Token.Type.INT, tt[2].type)

        tt = parseLyng("5 ..< 4".toSource())

        assertEquals(Token.Type.INT, tt[0].type)
        assertEquals(Token.Type.DOTDOTLT, tt[1].type)
        assertEquals(Token.Type.INT, tt[2].type)
    }

    @Test
    fun parseInTest() {
        var tt = parseLyng("5 in 4".toSource())

        assertEquals(Token.Type.INT, tt[0].type)
        assertEquals(Token.Type.IN, tt[1].type)
        assertEquals(Token.Type.INT, tt[2].type)

        tt = parseLyng("5 ..< 4".toSource())

        assertEquals(Token.Type.INT, tt[0].type)
        assertEquals(Token.Type.DOTDOTLT, tt[1].type)
        assertEquals(Token.Type.INT, tt[2].type)
    }

    @Test
    fun parserLabelsTest() {
        val src = "label@ break@label".toSource()
        val tt = parseLyng(src)
        assertEquals(Token("label", src.posAt(0, 0), Token.Type.LABEL), tt[0])
        assertEquals(Token("break", src.posAt(0, 7), Token.Type.ID), tt[1])
        assertEquals(Token("label", src.posAt(0, 12), Token.Type.ATLABEL), tt[2])
    }

//    @Test
//    fun parse0Test() {
//        val src = """
//            println("Hello")
//            println( "world" )
//        """.trimIndent().toSource()
//
//        val p = parseLyng(src).listIterator()
//
//        assertEquals(Token("println", src.posAt(0, 0), Token.Type.ID), p.next())
//        assertEquals(Token("(", src.posAt(0, 7), Token.Type.LPAREN), p.next())
//        assertEquals(Token("Hello", src.posAt(0, 9), Token.Type.STRING), p.next())
//        assertEquals(Token(")", src.posAt(0, 15), Token.Type.RPAREN), p.next())
//        assertEquals(Token("\n", src.posAt(0, 16), Token.Type.NEWLINE), p.next())
//        assertEquals(Token("println", src.posAt(1, 0), Token.Type.ID), p.next())
//        assertEquals(Token("(", src.posAt(1, 7), Token.Type.LPAREN), p.next())
//        assertEquals(Token("world", src.posAt(1, 9), Token.Type.STRING), p.next())
//        assertEquals(Token(")", src.posAt(1, 17), Token.Type.RPAREN), p.next())
//    }

    @Test
    fun parse1Test() {
        val src = "2 + 7".toSource()

        val p = parseLyng(src).listIterator()

        assertEquals(Token("2", src.posAt(0, 0), Token.Type.INT), p.next())
        assertEquals(Token("+", src.posAt(0, 2), Token.Type.PLUS), p.next())
        assertEquals(Token("7", src.posAt(0, 4), Token.Type.INT), p.next())
    }

    @Test
    fun compileNumbersTest() = runTest {
        assertEquals(ObjInt(17), eval("17"))
        assertEquals(ObjInt(17), eval("+17"))
        assertEquals(ObjInt(-17), eval("-17"))


        assertEquals(ObjInt(1970), eval("1900 + 70"))
        assertEquals(ObjInt(1970), eval("2000 - 30"))

//        assertEquals(ObjReal(3.14), eval("3.14"))
        assertEquals(ObjReal(314.0), eval("3.14e2"))
        assertEquals(ObjReal(314.0), eval("100 3.14e2"))
        assertEquals(ObjReal(314.0), eval("100\n 3.14e2"))
    }

    @Test
    fun compileBuiltinCallsTest() = runTest {
//        println(eval("π"))
//        val pi = eval("Math.PI")
//        assertIs<ObjReal>(pi)
//        assertTrue(pi.value - PI < 0.000001)
//        assertTrue(eval("Math.PI+1").toDouble() - PI - 1.0 < 0.000001)

//        assertTrue(eval("sin(Math.PI)").toDouble() - 1 < 0.000001)
        assertTrue(eval("sin(π)").toDouble() - 1 < 0.000001)
    }

    @Test
    fun varsAndConstsTest() = runTest {
        val scope = Scope(pos = Pos.builtIn)
        assertEquals(
            ObjInt(3L), scope.eval(
                """
            val a = 17
            var b = 3
        """.trimIndent()
            )
        )
        assertEquals(17, scope.eval("a").toInt())
        assertEquals(20, scope.eval("b + a").toInt())
        assertFailsWith<ScriptError> {
            scope.eval("a = 10")
        }
        assertEquals(17, scope.eval("a").toInt())
        assertEquals(5, scope.eval("b = a - 7 - 5").toInt())
        assertEquals(5, scope.eval("b").toInt())
    }

    @Test
    fun functionTest() = runTest {
        val scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            fun foo(a, b) {
                a + b
            }
        """.trimIndent()
        )
        assertEquals(17, scope.eval("foo(3,14)").toInt())
        assertFailsWith<ScriptError> {
            assertEquals(17, scope.eval("foo(3)").toInt())
        }

        scope.eval(
            """
            fn bar(a, b=10) {
                a + b + 1
            }
        """.trimIndent()
        )
        assertEquals(10, scope.eval("bar(3, 6)").toInt())
        assertEquals(14, scope.eval("bar(3)").toInt())
    }

    @Test
    fun simpleClosureTest() = runTest {
        val scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            var global = 10
            
            fun foo(a, b) {
                global + a + b
            }
        """.trimIndent()
        )
        assertEquals(27, scope.eval("foo(3,14)").toInt())
        scope.eval("global = 20")
        assertEquals(37, scope.eval("foo(3,14)").toInt())
    }

    @Test
    fun nullAndVoidTest() = runTest {
        val scope = Scope(pos = Pos.builtIn)
        assertEquals(ObjVoid, scope.eval("void"))
        assertEquals(ObjNull, scope.eval("null"))
    }

    @Test
    fun arithmeticOperatorsTest() = runTest {
        assertEquals(2, eval("5/2").toInt())
        assertEquals(2.5, eval("5.0/2").toDouble())
        assertEquals(2.5, eval("5/2.0").toDouble())
        assertEquals(2.5, eval("5.0/2.0").toDouble())

        assertEquals(1, eval("5%2").toInt())
        assertEquals(1.0, eval("5.0%2").toDouble())

        assertEquals(77, eval("11 * 7").toInt())

        assertEquals(2.0, eval("floor(5.0/2)").toDouble())
        assertEquals(3, eval("ceil(5.0/2)").toInt())

        assertEquals(2.0, eval("round(4.7/2)").toDouble())
        assertEquals(3.0, eval("round(5.1/2)").toDouble())
    }

    @Test
    fun arithmetics() = runTest {
        // integer
        assertEquals(17, eval("2 + 3 * 5").toInt())
        assertEquals(4, eval("5-1").toInt())
        assertEquals(2, eval("8/4").toInt())
        assertEquals(2, eval("8 % 3").toInt())

        // int-real
        assertEquals(9.5, eval("2 + 3 * 2.5").toDouble())
        assertEquals(4.5, eval("5 - 0.5").toDouble())
        assertEquals(2.5, eval("5 / 2.0").toDouble())
        assertEquals(2.5, eval("5.0 / 2.0").toDouble())

        // real
        assertEquals(7.5, eval("2.5 + 5.0").toDouble())
        assertEquals(4.5, eval("5.0 - 0.5").toDouble())
        assertEquals(12.5, eval("5.0 * 2.5").toDouble())
        assertEquals(2.5, eval("5.0 / 2.0").toDouble())
    }

    @Test
    fun arithmeticParenthesisTest() = runTest {
        assertEquals(17, eval("2.0 + 3 * 5").toInt())
        assertEquals(17, eval("2 + (3 * 5)").toInt())
        assertEquals(25, eval("(2 + 3) * 5").toInt())
        assertEquals(24, eval("(2 + 3) * 5 -1").toInt())
    }

    @Test
    fun stringOpTest() = runTest {
        assertEquals("foobar", eval(""" "foo" + "bar" """).toString())
        assertEquals("foo17", eval(""" "foo" + 17 """).toString())
    }

    @Test
    fun eqNeqTest() = runTest {
        assertEquals(ObjBool(true), eval("val x = 2; x == 2"))
        assertEquals(ObjFalse, eval("val x = 3; x == 2"))
        assertEquals(ObjBool(true), eval("val x = 3; x != 2"))
        assertEquals(ObjFalse, eval("val x = 3; x != 3"))

        assertTrue { eval("1 == 1").toBool() }
        assertTrue { eval("true == true").toBool() }
        assertTrue { eval("true != false").toBool() }
        assertFalse { eval("true == false").toBool() }
        assertFalse { eval("false != false").toBool() }

        assertTrue { eval("2 == 2 && 3 != 4").toBool() }
    }

    @Test
    fun logicTest() = runTest {
        assertEquals(ObjFalse, eval("true && false"))
        assertEquals(ObjFalse, eval("false && false"))
        assertEquals(ObjFalse, eval("false && true"))
        assertEquals(ObjBool(true), eval("true && true"))

        assertEquals(ObjBool(true), eval("true || false"))
        assertEquals(ObjFalse, eval("false || false"))
        assertEquals(ObjBool(true), eval("false || true"))
        assertEquals(ObjBool(true), eval("true || true"))

        assertEquals(ObjFalse, eval("!true"))
        assertEquals(ObjBool(true), eval("!false"))
    }

    @Test
    fun gtLtTest() = runTest {
        assertTrue { eval("3 > 2").toBool() }
        assertFalse { eval("3 > 3").toBool() }
        assertTrue { eval("3 >= 2").toBool() }
        assertFalse { eval("3 >= 4").toBool() }
        assertFalse { eval("3 < 2").toBool() }
        assertFalse { eval("3 <= 2").toBool() }
        assertTrue { eval("3 <= 3").toBool() }
        assertTrue { eval("3 <= 4").toBool() }
        assertTrue { eval("3 < 4").toBool() }
        assertFalse { eval("4 < 3").toBool() }
        assertFalse { eval("4 <= 3").toBool() }
    }

    @Test
    fun ifTest() = runTest {
        // if - single line
        var scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            fn test1(n) {
                var result = "more"
                if( n >= 10 ) 
                    result = "enough"
                result
            }
        """.trimIndent()
        )
        assertEquals("enough", scope.eval("test1(11)").toString())
        assertEquals("more", scope.eval("test1(1)").toString())

        // if - multiline (block)
        scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            fn test1(n) {
                var prefix = "answer: "
                var result = "more"
                if( n >= 10 ) {
                    var prefix = "bad:" // local prefix 
                    prefix = "too bad:"
                    result = "enough"
                }
                prefix + result
            }
        """.trimIndent()
        )
        assertEquals("answer: enough", scope.eval("test1(11)").toString())
        assertEquals("answer: more", scope.eval("test1(1)").toString())

        // else single line1
        scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            fn test1(n) {
                if( n >= 10 )
                    "enough"
                else
                    "more"
            }
        """.trimIndent()
        )
        assertEquals("enough", scope.eval("test1(11)").toString())
        assertEquals("more", scope.eval("test1(1)").toString())

        // if/else with blocks
        scope = Scope(pos = Pos.builtIn)
        scope.eval(
            """
            fn test1(n) {
                if( n > 20 ) {
                    "too much"
                } else if( n >= 10 ) {
                    "enough"
                }
                else {
                    "more"
                }
            }
        """.trimIndent()
        )
        assertEquals("enough", scope.eval("test1(11)").toString())
        assertEquals("more", scope.eval("test1(1)").toString())
        assertEquals("too much", scope.eval("test1(100)").toString())
    }

    @Test
    fun lateInitTest() = runTest {
        assertEquals(
            "ok", eval(
                """

            var late
            
            fun init() {
                late = "ok"
            }
            
            init()
            late
        """.trimIndent()
            ).toString()
        )

    }

    @Test
    fun whileAssignTest() = runTest {
        eval(
            """
            var t = 0
            val x = while( t < 5 ) { t++ }
            // last returned value is 4 - when t was 5 body was not executed
            assertEquals( 4, x )
        """.trimIndent()
        )
    }

    @Test
    fun whileTest() = runTest {
        assertEquals(
            5.0,
            eval(
                """
                var acc = 0
                while( acc < 5 ) acc = acc + 0.5
                acc
                """
            ).toDouble()
        )
        assertEquals(
            5.0,
            eval(
                """
                var acc = 0
                // return from while
                while( acc < 5 ) {
                    acc = acc + 0.5
                    acc
                }
                """
            ).toDouble()
        )
        assertEquals(
            3.0,
            eval(
                """
                var acc = 0
                while( acc < 5 ) {
                    acc = acc + 0.5
                    if( acc >= 3 ) break
                }

                acc

                """
            ).toDouble()
        )
        assertEquals(
            17.0,
            eval(
                """
                var acc = 0
                while( acc < 5 ) {
                    acc = acc + 0.5
                    if( acc >= 3 ) break 17
                }
                """
            ).toDouble()
        )
    }

    @Test
    fun testAssignArgumentsNoEllipsis() = runTest {
        // equal args, no ellipsis, no defaults, ok
        val ttEnd = Token.Type.RBRACE
        var pa = ArgsDeclaration(
            listOf(
                ArgsDeclaration.Item("a"),
                ArgsDeclaration.Item("b"),
                ArgsDeclaration.Item("c"),
            ), ttEnd
        )
        var c = Scope(pos = Pos.builtIn, args = Arguments.from(listOf(1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        assertEquals(ObjInt(1), c["a"]?.value)
        assertEquals(ObjInt(2), c["b"]?.value)
        assertEquals(ObjInt(3), c["c"]?.value)
        // less args: error
        c = Scope(pos = Pos.builtIn, args = Arguments.from(listOf(1, 2).map { it.toObj() }))
        assertFailsWith<ScriptError> {
            pa.assignToContext(c)
        }
        // less args, no ellipsis, defaults, ok
        pa = ArgsDeclaration(
            listOf(
                ArgsDeclaration.Item("a"),
                ArgsDeclaration.Item("b"),
                ArgsDeclaration.Item("c", defaultValue = statement { ObjInt(100) }),
            ), ttEnd
        )
        pa.assignToContext(c)
        assertEquals(ObjInt(1), c["a"]?.value)
        assertEquals(ObjInt(2), c["b"]?.value)
        assertEquals(ObjInt(100), c["c"]?.value)
        // enough args. default value is ignored:
        c = Scope(pos = Pos.builtIn, args = Arguments.from(listOf(10, 2, 5).map { it.toObj() }))
        pa.assignToContext(c)
        assertEquals(ObjInt(10), c["a"]?.value)
        assertEquals(ObjInt(2), c["b"]?.value)
        assertEquals(ObjInt(5), c["c"]?.value)
    }

    @Test
    fun testAssignArgumentsEndEllipsis() = runTest {
        // equal args,
        // less args, no ellipsis, defaults, ok
        val ttEnd = Token.Type.RBRACE
        val pa = ArgsDeclaration(
            listOf(
                ArgsDeclaration.Item("a"),
                ArgsDeclaration.Item("b", isEllipsis = true),
            ), ttEnd
        )
        var c = Scope(args = Arguments.from(listOf(1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assert( a == 1 ); println(b)")
        c.eval("assert( b == [2,3] )")

        c = Scope(args = Arguments.from(listOf(1, 2).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( a, 1 ); println(b)")
        c.eval("assertEquals( b, [2] )")

        c = Scope(args = Arguments.from(listOf(1).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assert( a == 1 ); println(b)")
        c.eval("assert( b == [] )")
    }

    @Test
    fun testAssignArgumentsStartEllipsis() = runTest {
        val ttEnd = Token.Type.RBRACE
        val pa = ArgsDeclaration(
            listOf(
                ArgsDeclaration.Item("a", isEllipsis = true),
                ArgsDeclaration.Item("b"),
                ArgsDeclaration.Item("c"),
            ), ttEnd
        )
        var c = Scope(args = Arguments.from(listOf(0, 1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( a,[0,1] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( a,[1] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( a,[] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(3).map { it.toObj() }))
        assertFailsWith<ExecutionError> {
            pa.assignToContext(c)
        }
    }

    @Test
    fun testAssignArgumentsMiddleEllipsis() = runTest {
        val ttEnd = Token.Type.RBRACE
        val pa = ArgsDeclaration(
            listOf(
                ArgsDeclaration.Item("i"),
                ArgsDeclaration.Item("a", isEllipsis = true),
                ArgsDeclaration.Item("b"),
                ArgsDeclaration.Item("c"),
            ), ttEnd
        )
        var c = Scope(args = Arguments.from(listOf(-1, 0, 1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( i, -1 )")
        c.eval("assertEquals( a,[0,1] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(0, 1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( i, 0 )")
        c.eval("assertEquals( a,[1] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(1, 2, 3).map { it.toObj() }))
        pa.assignToContext(c)
        c.eval("assertEquals( i, 1)")
        c.eval("assertEquals( a,[] )")
        c.eval("assertEquals( b, 2 )")
        c.eval("assertEquals( c, 3 )")

        c = Scope(args = Arguments.from(listOf(2, 3).map { it.toObj() }))
        assertFailsWith<ExecutionError> {
            pa.assignToContext(c)
        }
    }

    @Test
    fun testWhileBlockIsolation1() = runTest {
        eval(
            """
                var x = 100
                var cnt = 2
                while( cnt-- > 0 ) {
                    var x = cnt + 1
                    assert(x == cnt + 1)
                }
                assert( x == 100 )
                assert( cnt == -1 )
            """.trimIndent()
        )
    }

    @Test
    fun testWhileBlockIsolation2() = runTest {
        assertFails {
            eval(
                """
                var cnt = 2
                while( cnt-- > 0 ) {
                    var inner = cnt + 1
                    assert(inner == cnt + 1)
                }
                println("inner "+inner)
            """.trimIndent()
            )
        }
    }

    @Test
    fun testWhileBlockIsolation3() = runTest {
        eval(
            """
                var outer = 7
                var sum = 0
                var cnt1 = 0
                val initialForCnt2 = 0
                while( ++cnt1 < 3 ) {
                    var cnt2 = initialForCnt2
                 
                    assert(cnt2 == 0)
                    assert(outer == 7)
                 
                    while(++cnt2 < 5) {
                        assert(initialForCnt2 == 0)
                        var outer = 1
                        sum = sum + outer
                    }
                }
                println("sum "+sum)
            """.trimIndent()
        )
    }

    @Test
    fun whileNonLocalBreakTest() = runTest {
        assertEquals(
            "ok2:3:7", eval(
                """
            var t1 = 10
            outer@ while( t1 > 0 ) {
                var t2 = 10
                while( t2 > 0 ) {
                    t2 = t2 - 1
                    if( t2 == 3 && t1 == 7) {
                        break@outer "ok2:"+t2+":"+t1
                    }
                }
                --t1
            }
        """.trimIndent()
            ).toString()
        )
    }

    @Test
    fun bookTest0() = runTest {
        assertEquals(
            "just 3",
            eval(
                """
                val count = 3
                val res = if( count > 10 ) "too much" else "just " + count
                res
                """.trimIndent()
            )
                .toString()
        )
        assertEquals(
            "just 3",
            eval(
                """
                val count = 3
                var res = if( count > 10 ) "too much" else "it's " + count
                res = if( count > 10 ) "too much" else "just " + count
                res
                """.trimIndent()
            )
                .toString()
        )
    }

    @Test
    fun testIncr() = runTest {
        val c = Scope()
        c.eval("var x = 10")
        assertEquals(10, c.eval("x++").toInt())
        assertEquals(11, c.eval("x++").toInt())
        assertEquals(12, c.eval("x").toInt())

        assertEquals(12, c.eval("x").toInt())
        assertEquals(12, c.eval("x").toInt())
    }

    @Test
    fun testDecr() = runTest {
        val c = Scope()
        c.eval("var x = 9")
        assertEquals(9, c.eval("println(x); val a = x--; println(x); println(a); a").toInt())
        assertEquals(8, c.eval("x--").toInt())
        assertEquals(7, c.eval("x--").toInt())
        assertEquals(6, c.eval("x--").toInt())
        assertEquals(5, c.eval("x").toInt())
    }

    @Test
    fun testDecrIncr() = runTest {
        val c = Scope()
        c.eval("var x = 9")
        assertEquals(9, c.eval("x++").toInt())
        assertEquals(10, c.eval("x++").toInt())
        assertEquals(11, c.eval("x").toInt())
        assertEquals(11, c.eval("x--").toInt())
        assertEquals(10, c.eval("x--").toInt())
        assertEquals(9, c.eval("x--").toInt())
        assertEquals(8, c.eval("x--").toInt())
        assertEquals(7, c.eval("x + 0").toInt())
    }

    @Test
    fun testDecrIncr2() = runTest {
        val c = Scope()
        c.eval("var x = 9")
        assertEquals(9, c.eval("x--").toInt())
        assertEquals(8, c.eval("x--").toInt())
        assertEquals(7, c.eval("x--").toInt())
        assertEquals(6, c.eval("x").toInt())
        assertEquals(6, c.eval("x++").toInt())
        assertEquals(7, c.eval("x++").toInt())
        assertEquals(
            8, c.eval("x")
                .also {
                    println("${it.toDouble()} ${it.toInt()} ${it.toLong()} ${it.toInt()}")
                }
                .toInt())
    }

    @Test
    fun testDecrIncr3() = runTest {
        val c = Scope()
        c.eval("var x = 9")
        assertEquals(9, c.eval("x++").toInt())
        assertEquals(10, c.eval("x++").toInt())
        assertEquals(11, c.eval("x++").toInt())
        assertEquals(12, c.eval("x").toInt())
        assertEquals(12, c.eval("x--").toInt())
        assertEquals(11, c.eval("x").toInt())
    }

    @Test
    fun testIncrAndDecr() = runTest {
        val c = Scope()
        assertEquals(
            "8", c.eval(
                """
            var x = 5
            x-- 
            x-- 
            x++ 
            x * 2
        """
            ).toString()
        )

        assertEquals("4", c.eval("x").toString())
//        assertEquals( "8", c.eval("x*2").toString())
//        assertEquals( "4", c.eval("x+0").toString())
    }

    @Test
    fun bookTest2() = runTest {
        val src = """
        fn check(amount, prefix = "answer: ") {
            prefix + if( amount > 100 )
                 "enough"
             else
                 "more"
           
         }
         """.trimIndent()
        eval(src)
    }

    @Test
    fun testAssign1() = runTest {
        assertEquals(10, eval("var x = 5; x=10; x").toInt())
        val ctx = Scope()
        ctx.eval(
            """
            var a = 1
            var b = 1
        """.trimIndent()
        )
        assertEquals(3, ctx.eval("a + a + 1").toInt())
        assertEquals(12, ctx.eval("a + (b = 10) + 1").toInt())
        assertEquals(10, ctx.eval("b").toInt())
    }

    @Test
    fun testAssign2() = runTest {
        val ctx = Scope()
        ctx.eval("var x = 10")
        assertEquals(14, ctx.eval("x += 4").toInt())
        assertEquals(14, ctx.eval("x").toInt())
        assertEquals(12, ctx.eval("x -= 2").toInt())
        assertEquals(12, ctx.eval("x").toInt())

        assertEquals(24, ctx.eval("x *= 2").toInt())
        assertEquals(24, ctx.eval("x").toInt())

        assertEquals(12, ctx.eval("x /= 2").toInt())
        assertEquals(12, ctx.eval("x").toInt())

        assertEquals(2, ctx.eval("x %= 5").toInt())
    }

    @Test
    fun testVals() = runTest {
        val cxt = Scope()
        cxt.eval("val x = 11")
        assertEquals(11, cxt.eval("x").toInt())
        assertFails { cxt.eval("x = 12") }
        assertFails { cxt.eval("x += 12") }
        assertFails { cxt.eval("x -= 12") }
        assertFails { cxt.eval("x *= 2") }
        assertFails { cxt.eval("x /= 2") }
        assertFails { cxt.eval("x++") }
        assertFails { cxt.eval("++x") }
        assertFails { cxt.eval("x--") }
        assertFails { cxt.eval("--x") }
        assertEquals(11, cxt.eval("x").toInt())
    }

    @Test
    fun testValVarConverting() = runTest {
        eval(
            """
            val x = 5
            var y = x
            y = 1
            assert(x == 5)
        """.trimIndent()
        )
        assertFails {
            eval(
                """
                val x = 5
                fun fna(t) {
                    t = 11
                }
                fna(1)
        """.trimIndent()
            )
        }
        eval(
            """
            var x = 5
            val y = x
            x = 10
            assert(y == 5)
            assert(x == 10)
        """.trimIndent()
        )
    }

    @Test
    fun testListLiteral() = runTest {
        eval(
            """
            val list = [1,22,3]
            assert(list[0] == 1)
            assert(list[1] == 22)
            assert(list[2] == 3)
        """.trimIndent()
        )

        eval(
            """
            val x0 = 100
            val list = [x0 + 1, x0 * 10, 3]
            assert(list[0] == 101)
            assert(list[1] == 1000)
            assert(list[2] == 3)
        """.trimIndent()
        )

        eval(
            """
            val x0 = 100
            val list = [x0 + 1, x0 * 10, if(x0 < 100) "low" else "high", 5]
            assert(list[0] == 101)
            assert(list[1] == 1000)
            assert(list[2] == "high")
            assert(list[3] == 5)
        """.trimIndent()
        )

    }

    @Test
    fun testListLiteralSpread() = runTest {
        eval(
            """
            val list1 = [1,22,3]
            val list = ["start", ...list1, "end"]
            assert(list[0] == "start")
            assert(list[1] == 1)
            assert(list[2] == 22)
            assert(list[3] == 3)
            assert(list[4] == "end")
        """.trimIndent()
        )
    }

    @Test
    fun testListSize() = runTest {
        eval(
            """
            val a = [4,3]
            assert(a.size == 2)
            assert( 3 == a[1] )
        """.trimIndent()
        )
    }

    @Test
    fun testArrayCompare() = runTest {
        eval(
            """
            val a = [4,3]
            val b = [4,3]
            assert(a == b)
            assert( a === a )
            assert( !(a === b) )
            assert( a !== b )
        """.trimIndent()
        )
    }

    @Test
    fun forLoop1() = runTest {
        eval(
            """
            var sum = 0
            for(i in [1,2,3]) {
                println(i)
                sum += i
            }
            assert(sum == 6)
        """.trimIndent()
        )
        eval(
            """
            fun test1(array) {
                var sum = 0
                for(i in array) {
                    if( i > 2 ) break "too much"
                    sum += i
                }
            }
            println("result=",test1([1,2]))
            println("result=",test1([1,2,3]))
        """.trimIndent()
        )
    }

    @Test
    fun forLoop2() = runTest {
        println(
            eval(
                """
            fun search(haystack, needle) {    
                for(ch in haystack) {
                    if( ch == needle) 
                        break "found"
                }
                else null
            }
            assert( search("hello", 'l') == "found")
            assert( search("hello", 'z') == null)
        """.trimIndent()
            ).toString()
        )
    }

    @Test
    fun testIntClosedRangeInclusive() = runTest {
        eval(
            """
            val r = 10 .. 20
            assert( r::class == Range)
            assert(r.isOpen == false)
            assert(r.start == 10)
            assert(r.end == 20)
            assert(r.isEndInclusive == true)
            assert(r.isIntRange)
            
            assert(12 in r)
            assert(10 in r)
            assert(20 in r)
            
            assert(9 !in r)
            assert(21 !in r)
            
            assert( (11..12) in r)
            assert( (10..11) in r)
            assert( (11..20) in r)
            assert( (10..20) in r)
            
            assert( (9..12) !in r)
            assert( (1..9) !in r)
            assert( (17..22) !in r)
            assert( (21..22) !in r)
            
//            assert(r.size == 11)
        """.trimIndent()
        )
    }

    @Test
    fun testIntClosedRangeExclusive() = runTest {
        eval(
            """
            val r = 10 ..< 20
            assert( r::class == Range)
            assert(r.isOpen == false)
            assert(r.start == 10)
            assert(r.end == 20)
            assert(r.isEndInclusive == false)
            assert(r.isIntRange)
            
            assert(12 in r)
            assert(10 in r)
            assert(20 !in r)
            
            assert(9 !in r)
            assert(21 !in r)

            assert( (11..12) in r)
            assert( (10..11) in r)
            assert( (11..20) !in r)
            assert( (10..20) !in r)
            
            assert( (10..<20) in r)
            
            assert( (9..12) !in r)
            assert( (1..9) !in r)
            assert( (17..22) !in r)
            assert( (21..22) !in r)


        """.trimIndent()
        )
    }

    @Test
    fun testIntClosedRangeInExclusive() = runTest {
        eval(
            """
                assert( (1..3) !in (1..<3) )
                assert( (1..<3) in (1..3) )
            """.trimIndent()
        )
    }

    @Test
    fun testOpenStartRanges() = runTest {
        eval(
            """
            var r = ..5
            assert( r::class == Range)
            assert( r.start == null)
            assert( r.end == 5)
            assert( r.isEndInclusive)
            
            r = ..< 5
            assert( r::class == Range)
            assert( r.start == null)
            assert( r.end == 5)
            assert( !r.isEndInclusive)
            
            assert( r.start == null)
            
            assert( (-2..3) in r)
            assert( (-2..12) !in r)
            
        """.trimIndent()
        )
    }

    @Test
    fun testOpenEndRanges() = runTest {
        eval(
            """
            var r = 5..
            assert( r::class == Range)
            assert( r.end == null)
            assert( r.start == 5)
        """.trimIndent()
        )
    }

    @Test
    fun testOpenEndRanges2() = runTest {
        eval(
            """
            var r = 5..; var r2 = 6..
            val r3 = 7.. // open end
            assert( r::class == Range)
            assert( r.end == null)
            assert( r.start == 5)
            
            assert( r3::class == Range)
            assertEquals( r3.end, null)
            assert( r3.start == 7)
        """.trimIndent()
        )
    }

    @Test
    fun testOpenEndRanges3() = runTest {
        eval(
            """
            val r3 = 7.. // open end with comment
            assert( r3::class == Range)
            assertEquals( r3.end, null)
            assert( r3.start == 7)
        """.trimIndent()
        )
    }

    @Test
    fun testCharacterRange() = runTest {
        eval(
            """
            val x = '0'..'9'
            println(x)
            assert( '5' in x)
            assert( 'z' !in x)
            for( ch in x ) 
                println(ch)
        """.trimIndent()
        )
    }

    @Test
    fun testIs() = runTest {
        eval(
            """
            val x = 1..10
            assert( x is Range )
            assert( x is Iterable )
            assert( x !is String)
            assert( "foo" is String)
            
            assert( x is Iterable )
        """.trimIndent()
        )
    }

    @Test
    fun testForRange() = runTest {
        eval(
            """
            val x = 1..3
            val result = []
            for( i in x ) {
                println(i)
                result += (i*10)
            }
            assert( result == [10,20,30] )
            """.trimIndent()
        )
        val a = mutableListOf(1, 2)
        val b = listOf(3, 4)
        a += 10
        a += b
        println(a)
    }

    @Test
    fun testLambdaWithIt1() = runTest {
        eval(
            """
            val x = {
                it + "!" 
            }
            val y = if( 4 < 3 ) "NG" else "OK"
            assert( x::class == Callable)
            assert( x is Callable)
            assert(y == "OK")
            assert( x("hello") == "hello!")
        """.trimIndent()
        )
    }

    @Test
    fun testLambdaWithIt2() = runTest {
        eval(
            """
            val x = {
                assert(it == void)
            }
            assert( x() == void)
        """.trimIndent()
        )
    }

    @Test
    fun testLambdaWithIt3() = runTest {
        eval(
            """
            val x = {
                assert( it == [1,2,"end"])
            }
            println("0----")
            assert( x(1, 2, "end") == void)
        """.trimIndent()
        )
    }

    @Test
    fun testLambdaWithArgs() = runTest {
        eval(
            """
            val x = { x, y, z ->
                println("-- x=",x)
                println("-- y=",y)
                println("-- z=",z)
                println([x,y,z])
                assert( [x, y, z] == [1,2,"end"])
                println("----:")
            }
            assert( x(1, 2, "end") == void)
        """.trimIndent()
        )
    }

    @Test
    fun testCaptureLocals() = runTest {
        eval(
            """
            
            fun outer(prefix) {
                val p1 = "0" + prefix
                { 
                    p1 + "2" + it
                }
            }
            fun outer2(prefix) {
                val p1 = "*" + prefix
                { 
                    p1 + "2" + it
                }
            }
            val x = outer("1")
            val y = outer2("1")
            println(x("!"))
            assertEquals( "0123", x("3") )
            assertEquals( "*123", y("3") )
        """.trimIndent()
        )
    }

    @Test
    fun testInstanceCallScopeIsCorrect() = runTest {
        eval(
            """
            
            val prefix = ":"
            
            class T(text) {
                fun getText() { 
                    println(text)
                    prefix + text + "!" 
                }
            }
            
            val text = "invalid"
            
            val t1 = T("foo")
            val t2 = T("bar")
            
            // get inside the block
            for( i in 1..3 ) {
                assertEquals( "foo", t1.text )
                assertEquals( ":foo!", t1.getText() )
                assertEquals( "bar", t2.text )
                assertEquals( ":bar!", t2.getText() )
            }
        """.trimIndent()
        )
    }

    @Test
    fun testAppliedScopes() = runTest {
        eval(
            """
            class T(text) {
                fun getText() { 
                    println(text)
                    text + "!" 
                }
            }
        
            val prefix = ":"
        
            val text = "invalid"
            val t1 = T("foo")
            val t2 = T("bar")
            
            t1.apply { 
                // it must take "text" from class t1:
                assertEquals("foo", text)
                assertEquals( "foo!", getText() ) 
                assertEquals( ":foo!!", {
                    prefix + getText() + "!" 
                }()) 
            } 
            t2.apply { 
                assertEquals("bar", text)
                assertEquals( "bar!", getText() ) 
                assertEquals( ":bar!!", {
                    prefix + getText() + "!" 
                }()) 
            }
            // worst case: names clash
            fun badOne() {
                val prefix = "&"
                t1.apply {
                    assertEquals( ":foo!!", prefix + getText() + "!" ) 
                }
            }
            badOne()
            
            """.trimIndent()
        )
    }

    @Test
    fun testLambdaWithArgsEllipsis() = runTest {
        eval(
            """
            val x = { x, y... ->
                println("-- y=",y)
                println(":: "+y::class)
                assert( [x, ...y] == [1,2,"end"])
            }
            assert( x(1, 2, "end") == void)
            assert( x(1, ...[2, "end"]) == void)
        """.trimIndent()
        )
    }

    @Test
    fun testLambdaWithBadArgs() = runTest {
        assertFails {
            eval(
                """
            val x = { x, y ->
                void
            }
            assert( x(1, 2) == void)
            assert( x(1, ...[2, "end"]) == void)
        """.trimIndent()
            )
        }
    }

    @Test
    fun testWhileExecuteElseIfNotExecuted() = runTest {
        assertEquals(
            "ok",
            eval(
                """
                while( 5 < 1 ) {
                    "bad"
                } else "ok"
        """.trimIndent()
            ).toString()
        )
    }

    @Test
    fun testIsPrimeSampleBug() = runTest {
        eval(
            """
                fun naive_is_prime(candidate) {
                    val x = if( candidate !is Int) candidate.toInt() else candidate
                    var divisor = 1
                    println("start with ",x)
                    while( ++divisor < x/2 && divisor != 2 ) {
                        println("x=", x, " // ", divisor, " :: ", x % divisor)
                        if( x % divisor == 0 ) break false
                    }
                    else true
                }
                naive_is_prime(4)
    
        """.trimIndent()
        )
    }

    @Test
    fun testLambdaAsFnCallArg() = runTest {
        eval(
            """
            fun mapValues(iterable, transform) {
                println("start: ", transform)
                var result = []
                for( x in iterable ) result += transform(x)
            }
            assert( [11, 21, 31] == mapValues( if( true) [1,2,3] else [10], { it*10+1 }))
    """.trimIndent()
        )
    }

    @Test
    fun testNewFnParser() = runTest {
        eval(
            """
                fun f1(a,b) { a + b }
                println(f1(1,2))
                assertEquals( 7, f1(3,4) )
        """.trimIndent()
        )
    }

    @Test
    fun testSpoilArgsBug() = runTest {
        eval(
            """
        fun fnb(a,b) { a + b }
        
        fun fna(a, b) {
            val a0 = a
            val b0 = b
            fnb(a + 1, b + 1)
            assert( a0 == a )
            assert( b0 == b )
        }
        
        fna(5,6)
        """
        )
    }

    @Test
    fun testSpoilLamdaArgsBug() = runTest {
        eval(
            """
        val fnb = { a,b -> a + b }
        
         val fna = { a, b ->
            val a0 = a
            val b0 = b
            fnb(a + 1, b + 1)
            assert( a0 == a )
            assert( b0 == b )
        }
       
        fna(5,6)
        """
        )
    }

    @Test
    fun commentBlocksShouldNotAlterBehavior() = runTest {
        eval(
            """
            fun test() {    
                10
                /*
                */
                //val x = 11
            }
            assert( test() == 10 )
        """.trimIndent()
        )
    }

    @Test
    fun testShuttle() = runTest {
        eval(
            """
            assert( 5 <=> 3 > 0 )
            assert( 0 < 5 <=> 3  )
            assert( 5 <=> 5 == 0 )
            assert( 5 <=> 7 < 0 )
        """.trimIndent()
        )
    }

    @Test
    fun testSimpleStruct() = runTest {
        val c = Scope()
        c.eval(
            """
            class Point(x,y)
            assert( Point::class is Class )
            val p = Point(2,3)
            assert(p is Point)
            println(p)
            println(p.x)
            assert( p.x == 2 )
            assert( p.y == 3 )
            
            val p2 = Point(p.x+1,p.y+1)
            p.x = 0
            assertEquals( 0, p.x )
        """.trimIndent()
        )
    }

    @Test
    fun testNonAssignalbeFieldInStruct() = runTest {
        val c = Scope()
        c.eval(
            """
            class Point(x,y)
            val p = Point("2",3)
            assert(p is Point)
            assert( p.x == "2" )
            assert( p.y == 3 )
            
            p.x = 0
            assertEquals( 0, p.x )
        """.trimIndent()
        )
    }

    @Test
    fun testStructBodyVal() = runTest {
        val c = Scope()
        c.eval(
            """
            class Point(x,y) {
                val length = sqrt(x*x+y*y)
                var foo = "zero"
            }
            val p = Point(3,4)
            assertEquals(5, p.length)
            assertEquals("zero", p.foo)
            p.y = 10
            p.foo = "bar"
            assert( p.y == 10 )
            assert( p.foo == "bar")
            // length is a val, is shoud not change
            assert( p.length == 5 )
            """.trimIndent()
        )
    }

    @Test
    fun testStructBodyFun() = runTest {
        val c = Scope()
        c.eval(
            """
            class Point(x,y) {
                fun length() { 
                    sqrt(x*x+y*y) 
                }
                var foo = "zero"
            }
            val p = Point(3,4)
            assertEquals(5, p.length())
            p.y = 10
            println(p.length())
            assertEquals(sqrt(109), p.length())
            """.trimIndent()
        )
    }

    @Test
    fun testPrivateConstructorParams() = runTest {
        val c = Scope()
        c.eval(
            """
            class Point(private var x,y)
            val p = Point(1,2)
            p.y = 101
            assertThrows { p.x = 10 }
            """
        )
    }

    @Test
    fun testLBraceMethodCall() = runTest {
        eval(
            """
            class Foo() {
                fun cond(block) { 
                    block()
                }
            }
            val f = Foo()
            assertEquals( 1, f.cond { 1 } )
        """.trimIndent()
        )
    }

    @Test
    fun testLBraceFnCall() = runTest {
        eval(
            """
            fun cond(block) { 
                block()
            }
            assertEquals( 1, cond { 1 } )
        """.trimIndent()
        )
    }

    @Test
    fun testClasstoString() = runTest {
        eval(
            """
            class Point {
                var x
                var y
            }
            val p = Point()
            p.x = 1
            p.y = 2
            println(p)
        """.trimIndent()
        )
    }

    @Test
    fun testClassDefaultCompare() = runTest {
        eval(
            """
            class Point(x,y)
            assert( Point(1,2) == Point(1,2) )
            assert( Point(1,2) !== Point(1,2) )
            assert( Point(1,2) != Point(1,3) )
            assert( Point(1,2) < Point(2,2) )
            assert( Point(1,2) < Point(1,3) )
        """.trimIndent()
        )
    }

    @Test
    fun testAccessShortcuts() {
        assertTrue(Visibility.Public.isPublic)
        assertFalse(Visibility.Private.isPublic)
        assertFalse(Visibility.Protected.isPublic)
    }

    @Test
    fun segfault1Test() = runTest {
        eval(
            """
            
            fun findSumLimit(f) {
                var sum = 0.0
                for( n in 1..1000000 ) {
                    val s0 = sum
                    sum += f(n)
                    if( abs(sum - s0) < 0.00001 ) { 
                        println("limit reached after "+n+" rounds")
                        break sum
                    }
                    n++
                }
                else {
                    println("limit not reached")
                    null
                }
            }

            val limit = findSumLimit { n -> 1.0/n/n }
            
            println("Result: "+limit)
        """
        )
    }

    @Test
    fun testIntExponentRealForm() = runTest {
        when (val x = eval("1e-6").toString()) {
            "0.000001", "1E-6", "1e-6" -> {}
            else -> fail("Excepted 1e-6 got $x")
        }
//        assertEquals("1.0E-6", eval("1e-6").toString())
    }

    @Test
    fun testCallLastBlockAfterDetault() = runTest {
        eval(
            """
            // this means last is lambda:
            fun f(e=1, f) {
                "e="+e+"f="+f()
            }
            assertEquals("e=1f=xx", f { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun testCallLastBlockWithEllipsis() = runTest {
        eval(
            """
            // this means last is lambda:
            fun f(e..., f) {
                "e="+e+"f="+f()
            }
            assertEquals("e=[]f=xx", f { "xx" })
            assertEquals("e=[1,2]f=xx", f(1,2) { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun testMethodCallLastBlockAfterDefault() = runTest {
        eval(
            """
            class Foo {
                // this means last is lambda:
                fun test_f(e=1, f_param) {
                    "e="+e+"f="+f_param()
                }
            }
            val f_obj = Foo()
            assertEquals("e=1f=xx", f_obj.test_f { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun testMethodCallLastBlockWithEllipsis() = runTest {
        eval(
            """
            class Foo {
                // this means last is lambda:
                fun test_f_ellipsis(e..., f_param) {
                    "e="+e+"f="+f_param()
                }
            }
            val f_obj = Foo()
            assertEquals("e=[]f=xx", f_obj.test_f_ellipsis { "xx" })
            assertEquals("e=[1,2]f=xx", f_obj.test_f_ellipsis(1,2) { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun nationalCharsTest() = runTest {
        eval(
            """
            fun сумма_ряда(x, погрешность=0.0001, f) {
                var сумма = 0
                for( n in 1..100000) {
                    val следующая_сумма = сумма + f(x, n)
                    if( n > 1 && abs(следующая_сумма - сумма) < погрешность )
                        break следующая_сумма
                    сумма = следующая_сумма
                }
                else null
            }
            val x = сумма_ряда(1) { x, n -> 
                val sign = if( n % 2 == 1 ) 1 else -1
                sign * pow(x, n) / n
            }
            assert( x - ln(2) < 0.001 )
        """.trimIndent()
        )
    }

    @Test
    fun doWhileSimpleTest() = runTest {
        eval(
            """
            var sum = 0
            var x = do {
                val s = sum
                sum += 1
            } while( s < 10 )
            assertEquals(11, x)
        """.trimIndent()
        )
    }

    @Test
    fun testFailDoWhileSample1() = runTest {
        eval(
            """
            fun readLine() { "done: result" }
            val result = do {
                val line = readLine()
            } while( !line.startsWith("done:") )
            assertEquals("result", result.drop(6))
            result
        """.trimIndent()
        )
    }

    @Test
    fun testForContinue() = runTest {
        eval(
            """
            var x = 0
            for( i in 1..10 ) {
                if( i % 2 == 0 ) continue
                println(i)
                x++
            }
            assertEquals(5, x)
        """.trimIndent()
        )
    }

    @Test
    fun testForLabelNreakTest() = runTest {
        eval(
            """
            var x = 0
            var y = 0
            FOR0@ for( i in 1..10 ) {
                x = i
                for( j in 1..20 ) {
                    y = j
                    if( i == 3 && j == 5 ) {
                        println("bb")
                        break@FOR0
                    }
                }
            }
            assertEquals( 5, y )
            assertEquals( 3, x )
        """
        )
    }

    @Test
    fun testThrowExisting() = runTest {
        eval(
            """
            val x = IllegalArgumentException("test")
            assert( x is Exception )

            var t = 0
            var finallyCaught = false
            try {
                t = 1
                throw x
                t = 2
            }
            catch( e: SymbolNotDefinedException ) {
                t = 101
            }
            catch( e: IllegalArgumentException ) {
                t = 3
            }
            finally {
                finallyCaught = true
            }
            assertEquals(3, t)
            assert(finallyCaught)
        """.trimIndent()
        )
    }

    @Test
    fun testCatchShort1() = runTest {
        eval(
            """
            val x = IllegalArgumentException("test")
            var t = 0
            var finallyCaught = false
            try {
                t = 1
                throw x
                t = 2
            }
            catch(e) {
                t = 31
            }
            finally {
                finallyCaught = true
            }
            assertEquals(31, t)
            assert(finallyCaught)
        """.trimIndent()
        )
    }

    @Test
    fun testCatchShort2() = runTest {
        eval(
            """
            val x = IllegalArgumentException("test")
            var caught = null
            try {
                throw x
            }
            catch {
                caught = it
            }
            assert( caught is IllegalArgumentException )
        """.trimIndent()
        )
    }

    @Test
    fun testAccessEHData() = runTest {
        eval(
            """
            val x = IllegalArgumentException("test")
            val m = try {
                throw x
                null
            }
            catch(e) {
                println(e)
                println(e::class)
                println(e.message)
                println("--------------")
                e.message    
            }
            println(m)
            assert( m == "test" )
            """.trimIndent()
        )
    }

    @Test
    fun testTryFinally() = runTest {
        val c = Scope()
        assertFails {
            c.eval(
                """
                var resource = "used"
                try {
                    throw "test"
                }
                finally {
                    resource = "freed"
                }
            """.trimIndent()
            )
        }
        c.eval(
            """
            assertEquals("freed", resource)
        """.trimIndent()
        )
    }

    @Test
    fun testThrowFromKotlin() = runTest {
        val c = Script.newScope()
        c.addFn("callThrow") {
            raiseIllegalArgument("fromKotlin")
        }
        c.eval(
            """
            val result = try {
                callThrow()
                "fail"
            }
            catch(e) {
                println("caught:"+e)
                println(e.message)
                assert( e is IllegalArgumentException )
                assertEquals("fromKotlin", e.message)
                "ok"
            }
            assertEquals(result, "ok")
        """.trimIndent()
        )
    }

    @Test
    fun testReturnValue1() = runTest {
        val r = eval(
            """
            class Point(x,y) {
                println("1")
                fun length() { sqrt(d2()) }
                println("2")
                private fun d2() {x*x + y*y}
                println("3")
            }
            println("Helluva")
            val p = Point(3,4)
//            assertEquals( 5, p.length() )
//            assertThrows { p.d2() }
            "111"        
        """.trimIndent()
        )
        assertEquals("111", r.toString())
    }

    @Test
    fun doWhileValuesTest() = runTest {
        eval(
            """
            var count = 0    
            val result = do {
                count++
                if( count < 10 )
                    continue
                if( count % 2 == 1 )
                    break "found "+count
            } while(count < 100)
            else "no"
            assertEquals("found 11", result)
            """.trimIndent()
        )
        eval(
            """
            var count = 0    
            val result = do {
                count++
                if( count < 10 )
                    continue
                if( count % 2 == 1 )
                    break "found "+count
            } while(count < 5)
            else "no"
            assertEquals("no", result)
            """.trimIndent()
        )
        eval(
            """
            var count = 0    
            val result = do {
                count++
                if( count % 2 == 3 )
                    break "found "+count
                "proc "+count    
            } while(count < 5)
            assertEquals("proc 5", result)
            """.trimIndent()
        )
    }

    @Test
    fun doWhileValuesLabelTest() = runTest {
        withTimeout(5.seconds) {
            try {
                eval(
                    """
                var count = 0 
                var count2 = 0
                var count3 = 0
                val result = outer@ do {
                    count2++
                    count = 0
                    do {
                        count++
                        if( count < 10 || count2 < 5 ) {
                            continue
                        }
                        if( count % 2 == 1 )
                            break@outer "found "+count + "/" + count2
                    } while(count < 14)
                    count3++
                } while( count2 < 100 )
                else "no"
                assertEquals("found 11/5", result)
                assertEquals( 4, count3)
                """.trimIndent()
                )
            } catch (e: ExecutionError) {
                throw e
            }
        }
    }

    @Test
    fun testSimpleWhen() = runTest {
        eval(
            """
            var result = when("a") {
                "a" -> "ok"
                else -> "fail"
            }
            assertEquals(result, "ok")
            result = when(5) {
                3 -> "fail1"
                4 -> "fail2"
                else -> "ok2"
            }
            assert(result == "ok2")
            result = when(5) {
                3 -> "fail"
                4 -> "fail2"
            }
            assert(result == void)
        """.trimIndent()
        )
    }

    @Test
    fun testWhenIs() = runTest {
        eval(
            """
            var result = when("a") {
                is Int -> "fail2"
                is String -> "ok"
                else -> "fail"
            }
            assertEquals(result, "ok")
            result = when(5) {
                3 -> "fail1"
                4 -> "fail2"
                else -> "ok2"
            }
            assert(result == "ok2")
            result = when(5) {
                3 -> "fail"
                4 -> "fail2"
            }
            assert(result == void)
            result = when(5) {
                !is String -> "ok"
                4 -> "fail2"
            }
            assert(result == "ok")
        """.trimIndent()
        )
    }

    @Test
    fun testWhenIn() = runTest {
        eval(
            """
            var result = when('e') {
                in 'a'..'c' -> "fail2"
                in 'a'..'z' -> "ok"
                else -> "fail"
            }
//            assertEquals(result, "ok")
            result = when(5) {
                in [1,2,3,4,6] -> "fail1"
                in [7, 0, 9] -> "fail2"
                else -> "ok2"
            }
            assert(result == "ok2")
            result = when(5) {
                in [1,2,3,4,6] -> "fail1"
                in [7, 0, 9] -> "fail2"
                in [-1, 5, 11] -> "ok3"
                else -> "fail3"
            }
            assert(result == "ok3")
            result = when(5) {
                !in [1,2,3,4,6, 5] -> "fail1"
                !in [7, 0, 9, 5] -> "fail2"
                !in [-1, 15, 11] -> "ok4"
                else -> "fail3"
            }
            assert(result == "ok4")
            result = when(5) {
                in [1,3] -> "fail"
                in 2..4 -> "fail2"
            }
            assert(result == void)
        """.trimIndent()
        )
    }

    @Test
    fun testParseSpecialVars() {
        val l = parseLyng("$~".toSource("test$~"))
        println(l)
        assertEquals(Token.Type.ID, l[0].type)
        assertEquals("$~", l[0].value)
    }

    @Test
    fun testMatchOperator() = runTest {
        eval(
            """
            assert( "abc123".matches(".*\d{3}") )
            assert( ".*\d{3}".re =~ "abc123" )
            assert( "abc123" =~ ".*\d{3}".re )
            assert( "abc123" !~ ".*\d{4}".re )


            println($~)

            "abc123" =~ ".*(\d)(\d)(\d)$".re
            println($~)
            assertEquals("1", $~[1])
            """
        )
    }

    @Test
    fun testMatchingOperator2() = runTest {
        eval(
            """
            "abc123" =~ ".*(\d)(\d)(\d)$".re
            println($~)
            assertEquals("1", $~[1])
            assertEquals("2", $~[2])
            assertEquals("3", $~[3])
            assertEquals("abc123", $~[0])
        """.trimIndent()
        )
    }

//    @Test
//    fun testWhenMatch() = runTest {
//        eval(
//            """
//            when("abc123") {
//                ".*(\d)(\d)(\d)".re -> { x ->
//                    assertEquals("123", x[0])
//                }
//                else -> assert(false)
//            }
//            """.trimIndent()
//        )
//    }

    @Test
    fun testWhenSample1() = runTest {
        eval(
            """
            fun type(x) {
                when(x) {
                    in 'a'..'z', in 'A'..'Z' -> "letter"
                    in '0'..'9' -> "digit"
                    in "$%&" -> "hate char"
                    else -> "unknown"
                }
            }
            assertEquals("digit", type('3'))
            assertEquals("letter", type('E'))
            assertEquals("hate char", type('%'))
        """.trimIndent()
        )
    }

    @Test
    fun testWhenSample2() = runTest {
        eval(
            """
            fun type(x) {
                when(x) {
                    "42", 42 -> "answer to the great question"
                    is Real, is Int -> "number"
                    is String -> {
                        for( d in x ) {
                            if( d !in '0'..'9' ) 
                                break "unknown"
                        }
                        else "number"
                    }
                }
            }
            assertEquals("number", type(5))
    """.trimIndent()
        )
    }

    @Test
    fun testNull1() = runTest {
        eval(
            """
            var s = null
            assertThrows { s.length }
            assertThrows { s.size() }
            
            assertEquals( null, s?.size() )
            assertEquals( null, s?.length )
            assertEquals( null, s?.length ?{ "test" } )
            assertEquals( null, s?[1] )
            assertEquals( null, s ?{ "test" } )
            
            s = "xx"
            assert(s.lower().size == 2)
            assert(s.length == 2)
        """.trimIndent()
        )
    }

    @Test
    fun testSprintf() = runTest {
        eval(
            """ 
            assertEquals( "123.45", "%3.2f"(123.451678) )
            assertEquals( "123.45: hello", "%3.2f: %s"(123.451678, "hello") )
            assertEquals( "123.45: true", "%3.2f: %s"(123.451678, true) )
        """.trimIndent()
        )
    }

    @Test
    fun testSubstringRangeFailure() = runTest {
        eval(
            """ 
            assertEquals("pult", "catapult"[4..])
            assertEquals("cat", "catapult"[..2])
            """.trimIndent()
        )
    }

    @Test
    fun passingOpenEndedRangeAsParam() = runTest {
        eval(
            """
                fun test(r) {
                    assert( r is Range ) 
                }
                test( 1.. )
            """.trimIndent()
        )
    }

    @Test
    fun testCollectionStructure() = runTest {
        eval(
            """
                val list = [1,2,3]
                assert( 1 in list )
                assert( list.indexOf(3) == 2 )
                assert( list.indexOf(5) == -1 )
                assert( list is List )
                assert( list is Array )
                assert( list is Iterable )
                assert( list is Collection )
                
                val other = []
                list.forEach { other += it }
                assertEquals( list, other )
                
                assert( list.isEmpty() == false )
                
                assertEquals( [10, 20, 30], list.map { it * 10 } )
                assertEquals( [10, 20, 30], (1..3).map { it * 10 } )
                
            """.trimIndent()
        )
    }

    @Test
    fun testSet() = runTest {
        eval(
            """
            val set = Set(1,2,3)
            
            assert( set.contains(1) )
            assert( 1 in set )
            
            assert(set is Set)
            assert(set is Iterable)
            assert(set is Collection)
            println(set)
            for( x in set ) println(x)
            assert([1,2,3] == set.toList())
            set += 4
            assertEquals(set.toList(), [1,2,3,4])
            assert(set == Set(1,2,3,4))
            
            val s1 = [1, 2].toSet()
            assertEquals( Set(1,2), s1 * set) 
            
        """.trimIndent()
        )
    }

    @Test
    fun testSet2() = runTest {
        eval(
            """
            assertEquals( Set( ...[1,2,3]), Set(1,2,3) )
            assertEquals( Set( ...[1,false,"ok"]), Set("ok", 1, false) )
        """.trimIndent()
        )
    }

    @Test
    fun testSetAddRemoveSet() = runTest {
        eval(
            """
            val s1 = Set( 1, 2 3)
            val s2 = Set( 3, 4 5)
            assertEquals( Set(1,2,3,4,5), s1 + s2 )
            assertEquals( Set(1,2,3,4,5), s1 + s2.toList() )
            assertEquals( Set(1,2), s1 - s2 )
            assertEquals( Set(1,2), s1 - s2.toList() )
        """.trimIndent()
        )
    }

    @Test
    fun testLet() = runTest {
        eval(
            """
            class Point(x=0,y=0)
            assert( Point() is Object) 
            Point().let { println(it.x, it.y) }
            val x = null
            x?.let { println(it.x, it.y) }
        """.trimIndent()
        )
    }

    @Test
    fun testApply() = runTest {
        eval(
            """
            class Point(x,y)
            // see the difference: apply changes this to newly created Point:
            val p = Point(1,2).apply { 
                x++; y++ 
            }
            assertEquals(p, Point(2,3))
        """.trimIndent()
        )
    }

    @Test
    fun testApplyThis() = runTest {
        eval(
            """
            class Point(x,y)
            
            // see the difference: apply changes this to newly created Point:
            val p = Point(1,2).apply { 
                this.x++ 
                y++ 
            }
            assertEquals(p, Point(2,3))
        """.trimIndent()
        )
    }

    @Test
    fun testApplyFromStatic() = runTest {
        eval(
            """
                class Foo(value) {
                
                    fun test() {
                        "test: "+value
                    }
                    static val instance = Foo("bar")
                }
                
                Foo.instance.apply {
                    assertEquals("bar", value)
                    assertEquals("test: bar", test())
                }
                
        """.trimIndent()
        )
    }

    class ObjTestFoo(val value: ObjString) : Obj() {

        override val objClass: ObjClass = klass

        companion object {

            val klass = ObjClass("TestFoo").apply {
                addFn("test") {
                    thisAs<ObjTestFoo>().value
                }
            }
        }
    }

    @Test
    fun TestApplyFromKotlin() = runTest {
        val scope = Script.newScope()
        scope.addConst("testfoo", ObjTestFoo(ObjString("bar2")))
        scope.eval(
            """
            assertEquals(testfoo.test(), "bar2")
            testfoo.apply {
                println(test())
                assertEquals(test(), "bar2")
            }
        """.trimIndent()
        )
    }

    @Test
    fun testParallels() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(1.seconds) {
                val s = Script.newScope()
                s.eval(
                    """
                    fun dosomething() {
                        var x = 0
                        for( i in 1..100) {
                            x += i
                        }
                        delay(100)
                        assert(x == 5050)
                    }
                """.trimIndent()
                )
                (0..100).map {
                    globalDefer {
                        s.eval("dosomething()")
                    }
                }.toList().shuffled().forEach { it.await() }
            }
        }
    }

    @Test
    fun testParallels2() = runTest {
        withContext(Dispatchers.Default) {
            withTimeout(1.seconds) {
                val s = Script.newScope()
                s.eval(
                    """
                    // it is intentionally not optimal to provoke
                    // RC errors:
                    class AtomicCounter {
                        private val m = Mutex()
                        private var counter = 0
                        
                        fun increment() {
                            m.withLock { 
                                val a = counter
                                delay(1)
                                counter = a+1
                            }
                        }
                        
                        fun getCounter() { counter }
                    }
                    
                    val ac = AtomicCounter() 
                    
                    fun dosomething() {
                        var x = 0
                        for( i in 1..100) {
                            x += i
                        }
                        delay(100)
                        ac.increment()
                        x
                    }
                    
                    (1..50).map { launch { dosomething() } }.forEach { 
                        assertEquals(5050, it.await())
                     }
                     assertEquals( 50, ac.getCounter() )
                    
                """.trimIndent()
                )
            }
        }
    }

    @Test
    fun testExtend() = runTest() {
        eval(
            """
            
            fun Int.isEven() {
                this % 2 == 0
            }
            
            fun Object.isInteger() {
                println(this)
                println(this is Int)
                println(this is Real)
                println(this is String)
                when(this) {
                    is Int -> true
                    is Real -> toInt() == this
                    is String -> toReal().isInteger()
                    else -> false
                }
            }
            
            assert( 4.isEven() )
            assert( !5.isEven() )
            
            assert( 12.isInteger() == true )
            assert( 12.1.isInteger() == false )
            assert( "5".isInteger() )
            assert( !"5.2".isInteger() )
        """.trimIndent()
        )
    }

    @Test
    fun testToFlow() = runTest() {
        val c = Scope()
        val arr = c.eval("[1,2,3]")
        // array is iterable so we can:
        assertEquals(listOf(1, 2, 3), arr.toFlow(c).map { it.toInt() }.toList())
    }

    @Test
    fun testAssociateBy() = runTest() {
        eval(
            """
            val m = [123, 456].associateBy { "k:%s"(it) }
            println(m)
            assertEquals(123, m["k:123"])
            assertEquals(456, m["k:456"])
            """
        )
        listOf(1, 2, 3).associateBy { it * 10 }
    }

//    @Test
//    fun testImports1() = runTest() {
//        val foosrc = """
//            package lyng.foo
//
//            fun foo() { "foo1" }
//            """.trimIndent()
//        val pm = InlineSourcesPacman(Pacman.emptyAllowAll, listOf(Source("foosrc", foosrc)))
//        assertNotNull(pm.modules["lyng.foo"])
//        assertIs<ModuleScope>(pm.modules["lyng.foo"]!!.deferredModule.await())

//        assertEquals("foo1", pm.modules["lyng.foo"]!!.deferredModule.await().eval("foo()").toString())
//    }

    @Test
    fun testImports2() = runTest() {
        val foosrc = """
            package lyng.foo
            
            fun foo() { "foo1" }
            """.trimIndent()
        val pm = InlineSourcesImportProvider(listOf(Source("foosrc", foosrc)))

        val src = """
            import lyng.foo
            
            foo()
            """.trimIndent().toSource("test")

        val scope = ModuleScope(pm, src)
        assertEquals("foo1", scope.eval(src).toString())
    }

    @Test
    fun testImports3() = runTest {
        val foosrc = """
            package lyng.foo
            
            import lyng.bar            
            
            fun foo() { "foo1" }
            """.trimIndent()
        val barsrc = """
            package lyng.bar
            
            fun bar() { "bar1" }
            """.trimIndent()
        val pm = InlineSourcesImportProvider(
            listOf(
                Source("barsrc", barsrc),
                Source("foosrc", foosrc),
            )
        )

        val src = """
            import lyng.foo
            
            foo() + " / " + bar()
            """.trimIndent().toSource("test")

        val scope = ModuleScope(pm, src)
        assertEquals("foo1 / bar1", scope.eval(src).toString())
    }

    @Test
    fun testImportsCircular() = runTest {
        val foosrc = """
            package lyng.foo
            
            import lyng.bar            
            
            fun foo() { "foo1" }
            """.trimIndent()
        val barsrc = """
            package lyng.bar
            
            import lyng.foo
            
            fun bar() { "bar1" }
            """.trimIndent()
        val pm = InlineSourcesImportProvider(
            listOf(
                Source("barsrc", barsrc),
                Source("foosrc", foosrc),
            )
        )

        val src = """
            import lyng.bar
            
            foo() + " / " + bar()
            """.trimIndent().toSource("test")

        val scope = ModuleScope(pm, src)
        assertEquals("foo1 / bar1", scope.eval(src).toString())
    }

    @Test
    fun testDefaultImportManager() = runTest {
        val scope = Scope.new()
        assertFails {
            scope.eval(
                """
                import foo
                foo()
                """.trimIndent()
            )
        }
        scope.importManager.addTextPackages(
            """
            package foo
            
            fun foo() { "bar" }
        """.trimIndent()
        )
        scope.eval(
            """
                import foo
                assertEquals( "bar", foo())
                """.trimIndent()
        )
    }

    @Test
    fun testMaps() = runTest {
        eval(
            """
                val map = Map( "a" => 1, "b" => 2 )
                assertEquals( 1, map["a"] )
                assertEquals( 2, map["b"] )
                assertEquals( null, map["c"] )
                map["c"] = 3
                assertEquals( 3, map["c"] )
        """.trimIndent()
        )
    }

    @Test
    fun testMapAsDelegate() = runTest {
        eval(
            """
                val m = { a: 1, b: 2 }
                assert(m is Delegate)
                val a by m
                var b by m
                assertEquals(1, a)
                assertEquals(2, b)
                b = 42
                assertEquals(42, m["b"])
                assertEquals(1, a)
                assertEquals(1, m["a"])
            """.trimIndent()
        )
    }

    @Test
    fun testExternDeclarations() = runTest {
        eval(
            """
            extern fun hostFunction(a: Int, b: String): String
            extern class HostClass(name: String) {
                fun doSomething(): Int
                val status: String
            }
            extern object HostObject {
                fun getInstance(): HostClass
            }
            extern enum HostEnum {
                VALUE1, VALUE2
            }
            
            // These should not throw errors during compilation
            // and should be registered in the scope (though they won't have implementations here)
            // In this test environment, they might fail at runtime if called, but we just check compilation.
        """.trimIndent()
        )
    }

    @Test
    fun testExternExtension() = runTest {
        eval(
            """
            extern fun String.pretty(): String
            // Compiles without error
        """.trimIndent()
        )
    }

    @Test
    fun testBuffer() = runTest {
        eval(
            """
            import lyng.buffer
            
            assertEquals( 0, Buffer().size )
            assertEquals( 3, Buffer(1, 2, 3).size )
            assertEquals( 5, Buffer("hello").size )
            
            var buffer = Buffer("Hello")
            assertEquals( 5, buffer.size)
            assertEquals('l'.code, buffer[2] )
            assertEquals('l'.code, buffer[3] )
            assertEquals("Hello", buffer.decodeUtf8())
            
            buffer = buffer.toMutable()
            
            buffer[2] = 101
            assertEquals(101, buffer[2])
            assertEquals("Heelo", buffer.decodeUtf8())
            
        """.trimIndent()
        )
    }

    @Test
    fun testBufferEncodings() = runTest {
        eval(
            """
            import lyng.buffer
            
            val b = Buffer("hello")
            println(b.toDump())
            assertEquals( "hello", b.decodeUtf8() )
            
            println(b.base64)
            println(b.hex)

            assertEquals( b, Buffer.decodeBase64(b.base64) )
            assertEquals( b, Buffer.decodeHex(b.hex) )
            
            println(b.inspect())
                        
        """.trimIndent()
        )
    }

    @Test
    fun testBufferCompare() = runTest {
        eval(
            """
            import lyng.buffer
            
            println("Hello".characters())
            val b1 = Buffer("Hello")
            val b2 = Buffer("Hello".characters())
            
            assertEquals( b1, b2 )
            val b3 = b1 + Buffer("!")
            assertEquals( "Hello!", b3.decodeUtf8())
            assert( b3 > b1 )
            assert( b1 !== b2)
            
            val map = Map( b1 => "foo")
            assertEquals("foo",  map[b1])
            assertEquals("foo",  map[b2])
            assertEquals(null,  map[b3])
            
        """.trimIndent()
        )
    }

    @Test
    fun testInstant() = runTest {
        eval(
            """
            import lyng.time
            
            val now = Instant()
//            assertEquals( now.epochSeconds, Instant(now.epochSeconds).epochSeconds )

            assert( 10.seconds is Duration )
            assertEquals( 10.seconds, Duration(10) )
            assertEquals( 10.milliseconds, Duration(0.01) )
            assertEquals( 10.milliseconds, 0.01.seconds )
            assertEquals( 1001.5.milliseconds, 1.0015.seconds )

            val n1 = now + 7.seconds
            assert( n1 is Instant )

            assertEquals( n1 - now, 7.seconds )
            assertEquals( now - n1, -7.seconds )
            
            val t3 = Instant("2024-01-01T12:00:00.123456Z")
            val t4 = t3.truncateToMinute
            assertEquals(t4.toRFC3339(), "2024-01-01T12:00:00Z")
            assertEquals(
                "2024-01-01T12:00:00Z",
                Instant("2024-01-01T12:00:59.999Z").truncateToMinute().toRFC3339()
            )
             
            val t5 = t3.truncateToSecond
            assertEquals(t5.toRFC3339(), "2024-01-01T12:00:00Z")
            
            val t6 = t3.truncateToMillisecond
            assertEquals(t6.toRFC3339(), "2024-01-01T12:00:00.123Z")
            """.trimIndent()
        )
        delay(1000)
    }

    @Test
    fun testTimeStatics() = runTest {
        eval(
            """
            import lyng.time
            assert( 100.minutes is Duration )
            assert( 100.days is Duration )
            assert( 1.day == 24.hours )
            assert( 1.day.hours == 24 )
            assert( 1.hour.seconds == 3600 )
            assert( 1.minute.milliseconds == 60_000 )
            
            assert(Instant.distantFuture is Instant)
            assert(Instant.distantPast is Instant)
            assert( Instant.distantFuture - Instant.distantPast > 70_000_000.days)
            val maxRange = Instant.distantFuture - Instant.distantPast
            println("всего лет %g"(maxRange.days/365.2425))
            """.trimIndent()
        )
    }

    @Test
    fun testInstantFormatting() = runTest {
        eval(
            """
            import lyng.time
            val now = Instant()
            val unixEpoch = "%ts"(now)
            println("current seconds is %s"(unixEpoch))
            println("current time is %tT"(now))
            assertEquals( unixEpoch.toInt(), now.epochSeconds.toInt() )
            """.trimIndent()
        )
    }

    @Test
    fun testDateTimeComprehensive() = runTest {
        eval("""
            import lyng.time
            import lyng.serialization
            
            // 1. Timezone variations
            val t1 = Instant("2024-01-01T12:00:00Z")
            
            val dtZ = t1.toDateTime("Z")
            assertEquals(dtZ.timeZone, "Z")
            assertEquals(dtZ.hour, 12)
            
            val dtP2 = t1.toDateTime("+02:00")
            assertEquals(dtP2.timeZone, "+02:00")
            assertEquals(dtP2.hour, 14)
            
            val dtM330 = t1.toDateTime("-03:30")
            assertEquals(dtM330.timeZone, "-03:30")
            assertEquals(dtM330.hour, 8)
            assertEquals(dtM330.minute, 30)
            
            // 2. RFC3339 representations
            // Note: ObjDateTime.toString() currently uses localDateTime.toString() + timeZone.toString()
            // We should verify what it actually produces.
            val s1 = dtP2.toRFC3339()
            // kotlinx-datetime LocalDateTime.toString() is ISO8601
            // TimeZone.toString() for offsets is usually the offset string itself
            println("dtP2 RFC3339: " + s1)
            
            // 3. Parsing
            val t2 = Instant("2024-02-29T10:00:00+01:00")
            assertEquals(t2.toDateTime("Z").hour, 9)
            
            // val dt3 = DateTime(t1, "Europe/Prague")
            // assertEquals(dt3.timeZone, "Europe/Prague")
            
            // 4. Serialization (Lynon)
            val bin = Lynon.encode(dtP2)
            val dtP2_dec = Lynon.decode(bin)
            assertEquals(dtP2, dtP2_dec)
            assertEquals(dtP2_dec.hour, 14)
            assertEquals(dtP2_dec.timeZone, "+02:00")
            
            // 5. Serialization (JSON)
            // val json = Lynon.toJson(dtM330) // toJson is not on Lynon yet
            // println("JSON: " + json)
            
            // 6. Arithmetic edge cases
            val leapDay = Instant("2024-02-29T12:00:00Z").toDateTime("Z")
            val nextYear = leapDay.addYears(1)
            assertEquals(nextYear.year, 2025)
            assertEquals(nextYear.month, 2)
            assertEquals(nextYear.day, 28) // Normalized
            
            val monthEnd = Instant("2024-01-31T12:00:00Z").toDateTime("Z")
            val nextMonth = monthEnd.addMonths(1)
            assertEquals(nextMonth.month, 2)
            assertEquals(nextMonth.day, 29) // 2024 is leap year
            
            // 7. Day of week
            assertEquals(Instant("2024-01-01T12:00:00Z").toDateTime("Z").dayOfWeek, 1) // Monday
            assertEquals(Instant("2024-01-07T12:00:00Z").toDateTime("Z").dayOfWeek, 7) // Sunday
            
            // 8. DateTime to/from Instant
            val inst = dtP2.toInstant()
            assertEquals(inst, t1)
            assertEquals(dtP2.toEpochSeconds(), t1.epochWholeSeconds)
            
            // 9. toUTC and toTimeZone
            val dtUTC = dtP2.toUTC()
            assertEquals(dtUTC.timeZone, "UTC")
            assertEquals(dtUTC.hour, 12)
            
            val dtPrague = dtUTC.toTimeZone("+01:00")
            // Equivalent to Prague winter
            assertEquals(dtPrague.hour, 13)
            
            // 10. Component-based constructor
            val dtComp = DateTime(2024, 5, 20, 15, 30, 45, "+02:00")
            assertEquals(dtComp.year, 2024)
            assertEquals(dtComp.month, 5)
            assertEquals(dtComp.day, 20)
            assertEquals(dtComp.hour, 15)
            assertEquals(dtComp.minute, 30)
            assertEquals(dtComp.second, 45)
            assertEquals(dtComp.timeZone, "+02:00")
            
            // 11. parseRFC3339
            val dtParsed = DateTime.parseRFC3339("2024-05-20T15:30:45+02:00")
            assertEquals(dtParsed.year, 2024)
            assertEquals(dtParsed.hour, 15)
            assertEquals(dtParsed.timeZone, "+02:00")
            
            val dtParsedZ = DateTime.parseRFC3339("2024-05-20T15:30:45Z")
            assertEquals(dtParsedZ.timeZone, "Z")
            assertEquals(dtParsedZ.hour, 15)
            """.trimIndent())
    }

    @Test
    fun testInstantComponents() = runTest {
        eval("""
            import lyng.time
            val t1 = Instant("1970-05-06T07:11:56Z")
            val dt = t1.toDateTime("Z")
            assertEquals(dt.year, 1970)
            assertEquals(dt.month, 5)
            assertEquals(dt.day, 6)
            assertEquals(dt.hour, 7)
            assertEquals(dt.minute, 11)
            assertEquals(dt.second, 56)
            assertEquals(dt.dayOfWeek, 3) // 1970-05-06 was Wednesday
            assertEquals("1970-05-06T07:11:56Z", t1.toRFC3339())
            assertEquals("1970-05-06T07:11:56Z", t1.toSortableString())
            
            val dt2 = dt.toTimeZone("+02:00")
            assertEquals(dt2.hour, 9)
            assertEquals(dt2.timeZone, "+02:00")
            
            val dt3 = dt.addMonths(1)
            assertEquals(dt3.month, 6)
            assertEquals(dt3.day, 6)
            
            val dt4 = dt.addYears(1)
            assertEquals(dt4.year, 1971)
            
            assertEquals(dt.toInstant(), t1)
            """.trimIndent())
    }

    @Test
    fun testDoubleImports() = runTest {
        val s = Scope.new()
        println(Script.defaultImportManager.packageNames)
        println(s.importManager.packageNames)

        s.importManager.addTextPackages(
            """
            package foo
             
            import lyng.time
            
            fun foo() {
                println("foo: %s"(Instant()))
            }
        """.trimIndent()
        )
        s.importManager.addTextPackages(
            """
            package bar
             
            import lyng.time
            
            fun bar() {
                println("bar: %s"(Instant()))
            }
        """.trimIndent()
        )

        println(s.importManager.packageNames)

        s.eval(
            """
            import foo
            import bar
            
            foo()
            bar()
            
        """.trimIndent()
        )

    }

    @Test
    fun testIndexIntIncrements() = runTest {
        eval(
            """
        val x = [1,2,3]
        x[1]++
        ++x[0]
        assertEquals( [2,3,3], x )
        
        import lyng.buffer
        
        val b = MutableBuffer(1,2,3)
        b[1]++
        assert( b == Buffer(1,3,3) )
        ++b[0]
        assertEquals( b, Buffer(2,3,3) )
        """.trimIndent()
        )
    }

    @Test
    fun testIndexIntDecrements() = runTest {
        eval(
            """
        val x = [1,2,3]
        x[1]--
        --x[0]
        assertEquals( [0,1,3], x )
        
        import lyng.buffer
        
        val b = Buffer(1,2,3).toMutable()
        b[1]--
        assert( b == Buffer(1,1,3) )
        --b[0]
        assertEquals( b, Buffer(0,1,3) )
        """.trimIndent()
        )
    }

    @Test
    fun testRangeToList() = runTest {
        val x = eval("""(1..10).toList()""") as ObjList
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), x.list.map { it.toInt() })
        val y = eval("""(-2..3).toList()""") as ObjList
        println(y.list)
    }

    @Test
    fun testMultilineStrings() = runTest {
        assertEquals(
            """
            This is a multiline text.
            This is a second line.
        """.trimIndent(), eval(
                """
            "
                This is a multiline text.
                This is a second line.
            "
        """.trimIndent()
            ).toString()
        )
        assertEquals(
            """
            This is a multiline text.
        """.trimIndent(), eval(
                """
            "
                This is a multiline text.
            "
        """.trimIndent()
            ).toString()
        )
        assertEquals(
            """

            This is a multiline text.
        """.trimIndent(), eval(
                """
            "

                This is a multiline text.
            "
        """.trimIndent()
            ).toString()
        )
    }

    @Test
    fun tesFunAnnotation() = runTest {
        eval(
            """
        
            val exportedSymbols = Map()
            
            fun Exported(name, f, overrideName = null) {
                assertEquals(name, "getBalance")
                assertEquals(null, overrideName)
                exportedSymbols[ overrideName ?: name ] = f
                f
            }
            
            @Exported
            fun getBalance(x = 0) {
                121 + x
            }
            
            assert( exportedSymbols["getBalance"] != null )
            assertEquals(122, getBalance(1))
            
        """.trimIndent()
        )
    }

    @Test
    fun enumTest() = runTest {
        eval(
            """
                enum Color {
                    RED, GREEN, BLUE
                }
                
                assert( Color.RED is Color )
                assertEquals( 2, Color.BLUE.ordinal )
                assertEquals( "BLUE", Color.BLUE.name )

                assertEquals( [Color.RED,Color.GREEN,Color.BLUE], Color.entries)

                assertEquals( Color.valueOf("GREEN"), Color.GREEN )

                
                """.trimIndent()
        )
    }

    @Test
    fun enumSerializationTest() = runTest {
        eval(
            """
            import lyng.serialization
            
            enum Color {
                RED, GREEN, BLUE
            }

            val e = Lynon.encode(Color.BLUE)
            assertEquals( Color.BLUE, Lynon.decode(e) )
            println(e.toDump())

            val e1 = Lynon.encode( (1..1000).map { Color.GREEN } )
            println(e1.toDump())
            assert( e1.size / 1000.0 < 6)
            println(Lynon.encode( (1..100).map { "RED" } ).toDump() )
            
        """.trimIndent()
        )
    }

    @Test
    fun cachedTest() = runTest {
        eval(
            """
        
        var counter = 0
        var value = cached {
            counter++
            "ok"
        }
        
        assertEquals(0, counter)
        assertEquals("ok", value())
        assertEquals(1, counter)
        assertEquals("ok", value())
        assertEquals(1, counter)
        """.trimIndent()
        )
    }

    @Test
    fun testJoinToString() = runTest {
        eval(
            """
            assertEquals( (1..3).joinToString(), "1 2 3")
            assertEquals( (1..3).joinToString(":"), "1:2:3")
            assertEquals( (1..3).joinToString { it * 10 }, "10 20 30")
        """.trimIndent()
        )
    }

    @Test
    fun testElvisAndThrow() = runTest {
        eval(
            """
            val x = assertThrows {
                null ?: throw "test" + "x"
            }
            assertEquals( "testx", x.message)
        """.trimIndent()
        )
    }

    @Test
    fun testElvisAndThrow2() = runTest {
        eval(
            """
            val t = "112"
            val x = t ?: run { throw "testx" }
            }
            assertEquals( "112", x)
        """.trimIndent()
        )
    }

    @Test
    fun testElvisAndRunThrow() = runTest {
        eval(
            """
            val x = assertThrows {
                null ?: run { throw "testx" }
            }
            assertEquals( "testx", x.message)
        """.trimIndent()
        )
    }

    @Test
    fun testNewlinesAnsCommentsInExpressions() = runTest {
        assertEquals(
            2, (Scope().eval(
                """
            val e = 1 + 4 -
                3
        """.trimIndent()
            )).toInt()
        )

        eval(
            """
                val x = [1,2,3]
                    .map { it * 10 }
                    .map { it + 1 }
                assertEquals( [11,21,31], x)
            """.trimIndent()
        )
    }

    @Test
    fun testNotExpressionWithoutWs() = runTest {
        eval(
            """
            fun test() { false }
            class T(value)
            assert( !false )
            assert( !test() )
            assert( !test() )
            val t = T(false)
            assert( !t.value )
            assert( !if( true ) false else true )
        """.trimIndent()
        )
    }

    @Test
    fun testMultilineFnDeclaration() = runTest {
        eval(
            """
            fun test(
                x = 1,
                y = 2
            ) {
                x * y
            }
            assertEquals( 10, test(5) )
            assertEquals( 42, test(
                6,
                7
            ) )
        """.trimIndent()
        )
    }

    @Test
    fun testOverridenListToString() = runTest {
        eval(
            """
            val x = [1,2,3]
            assertEquals( "[1,2,3]", x.toString() )
        """.trimIndent()
        )
    }

    @Test
    fun testExceptionSerialization() = runTest {
        eval(
            """
                import lyng.serialization
                val x = [1,2,3]
                assertEquals( "[1,2,3]", x.toString() )
                try {    
                    require(false)
                }
                catch (e) {
                    println(e.stackTrace)
                    e.printStackTrace()
                    val coded = Lynon.encode(e)
                    val decoded = Lynon.decode(coded)
                    assertEquals( e::class, decoded::class )
                    assertEquals( e.stackTrace, decoded.stackTrace )
                    assertEquals( e.message, decoded.message )
                    println("-------------------- e")
                    println(e.toString())
                    println("-------------------- dee")
                    println(decoded.toString())
                    assertEquals( e.toString(), decoded.toString() )
                }
                """.trimIndent()
        )
    }

    @Test
    fun testExceptionSerializationPlain() = runTest {
        eval(
            """
                import lyng.serialization
                val x = [1,2,3]
                assertEquals( "[1,2,3]", x.toString() )
                try {    
                    throw "test"
                }
                catch (e) {
                    println(e.stackTrace)
                    e.printStackTrace()
                    val coded = Lynon.encode(e)
                    val decoded = Lynon.decode(coded)
                    assertEquals( e::class, decoded::class )
                    assertEquals( e.stackTrace, decoded.stackTrace )
                    assertEquals( e.message, decoded.message )
                    println("-------------------- e")
                    println(e.toString())
                    println("-------------------- dee")
                    println(decoded.toString())
                    assertEquals( e.toString(), decoded.toString() )
                }
                """.trimIndent()
        )
    }

    @Test
    fun testThisInClosure() = runTest {
        eval(
            """
            fun Iterable.sum2by(f) {
                var acc = null
                for( x in this ) {
                    println(x)
                    println(f(x))
                    acc = acc?.let { acc + f(x) } ?: f(x)
                }
            }
            class T(val coll, val factor) {
                fun sum() {
                    // here we use ths::T and it must be available:
                    coll.sum2by { it * factor }
                }
            }
            assertEquals(60, T([1,2,3], 10).sum())
        """.trimIndent()
        )
    }

    @Test
    fun testThisInFlowClosure() = runTest {
        eval(
            """
            class T(val coll, val factor) {
                fun seq() {
                    flow {
                        for( x in coll ) {
                            emit(x*factor)
                        }
                    }
                }
            }
            assertEquals([10,20,30], T([1,2,3], 10).seq().toList())
        """.trimIndent()
        )
    }

    @Test
    fun testSum() = runTest {
        eval(
            """
            assertEquals(1, [1].sum())
            assertEquals(null, [].sum())
            assertEquals(6, [1,2,3].sum())
            assertEquals(30, [3].sumOf { it * 10 })
            assertEquals(null, [].sumOf { it * 10 })
            assertEquals(60, [1,2,3].sumOf { it * 10 })
        """.trimIndent()
        )
    }

    @Test
    fun testSort() = runTest {
        eval(
            """
            val coll = [5,4,1,7]
            assertEquals( [1,4,5,7], coll.sortedWith { a,b -> a <=> b })
            assertEquals( [1,4,5,7], coll.sorted())
            assertEquals( [7,5,4,1], coll.sortedBy { -it })
            assertEquals( [1,4,5,7], coll.sortedBy { -it }.reversed())
        """.trimIndent()
        )
    }

    @Test
    fun testListSortInPlace() = runTest {
        eval(
            """
            val l1 = [6,3,1,9]
            l1.sort()
            assertEquals( [1,3,6,9], l1)
            l1.sortBy { -it }
            assertEquals( [1,3,6,9].reversed(), l1)
            l1.sort()
            l1.sortBy { it % 4 }
            // 1,3,6,9
            // 1 3 2 1
            // we hope we got it also stable:
            assertEquals( [1,9,6,3], l1)
        """
        )
    }

    @Test
    fun binarySearchTest() = runTest {
        eval(
            """
            val coll = [1,2,3,4,5]
            assertEquals( 2, coll.binarySearch(3) )
            assertEquals( 0, coll.binarySearch(1) )
            assertEquals( 4, coll.binarySearch(5) )
        """.trimIndent()
        )
    }

    @Test
    fun binarySearchTest2() = runTest {
        eval(
            """
            val src = (1..50).toList().shuffled()
            val result = []
            for( x in src ) {
                val i = result.binarySearch(x)
                assert( i < 0 )
                result.insertAt(-i-1, x)
            }
            assertEquals( src.sorted(), result )
            """.trimIndent()
        )
    }


//        @Test
    fun testMinimumOptimization() = runTest {
        for (i in 1..200) {
            bm {
                val x = Scope().eval(
                    """
                fun naiveCountHappyNumbers() {
                    var count = 0
                    for( n1 in 0..9 )
                        for( n2 in 0..9 )
                            for( n3 in 0..9 )
                                for( n4 in 0..9 )
                                    for( n5 in 0..9 )
                                        for( n6 in 0..9 )
                                            if( n1 + n2 + n3 == n4 + n5 + n6 ) count++
                    count
                }
                naiveCountHappyNumbers()
            """.trimIndent()
                ).toInt()
                assertEquals(55252, x)
            }
            delay(10)
        }
    }

    @Test
    fun testRegex1() = runTest {
        eval(
            """
            assert( ! "123".re.matches("abs123def") )
            assert( ".*123.*".re.matches("abs123def") )
//            assertEquals( "123", "123".re.find("abs123def")?.value )
//            assertEquals( "123", "[0-9]{3}".re.find("abs123def")?.value )
            assertEquals( "123", "\d{3}".re.find("abs123def")?.value )
            assertEquals( "123", "\\d{3}".re.find("abs123def")?.value )
            assertEquals( [1,2,3], "\d".re.findAll("abs123def").map { it.value.toInt() } )
            """
                .trimIndent()
        )
    }

    @Test
    fun extensionsMustBeLocalPerScope() = runTest {
        val scope1 = Script.newScope()

        // extension foo should be local to scope1
        assertEquals(
            "a_foo", scope1.eval(
                """
            fun String.foo() { this + "_foo" }
            "a".foo()
        """.trimIndent()
            ).toString()
        )

        val scope2 = Script.newScope()
        assertEquals(
            "a_bar", scope2.eval(
                """
            fun String.foo() { this + "_bar" }
            "a".foo()
        """.trimIndent()
            ).toString()
        )
    }

    @Test
    fun testThrowReportsSource() = runTest {
        try {
            eval(
                """
            // line 1
            // line 2
            throw "the test"
        """.trimIndent()
            )
        } catch (se: ScriptError) {
            println(se.message)
            // Pos.line is zero-based
            assertEquals(2, se.pos.line)
        }
    }

    @Test
    fun testRangeIsIterable() = runTest {
        eval(
            """
            val r = 1..10
            assert( r is Iterable )
        """.trimIndent()
        )
    }

    @Test
    fun testCallAndResultOrder() = runTest {
        eval(
            """
            import lyng.stdlib
            
            fun test(a="a", b="b", c="c") { [a, b, c] }
            
            // the parentheses here are in fact unnecessary:
            val ok1 = (test { void }).last() 
            assert( ok1 is Callable)
            
            // it should work without them, as the call test() {} must be executed
            // first, then the result should be used to call methods on it:
            
            // the parentheses here are in fact unnecessary:
            val ok2 = test { void }.last() 
            assert( ok2 is Callable)
        """.trimIndent()
        )

    }

    @Test
    fun testIterableMinMax() = runTest {
        eval(
            """
            import lyng.stdlib
            assertEquals( -100, (1..100).toList().minOf { -it } )
            assertEquals( -1, (1..100).toList().maxOf { -it } )
        """.trimIndent()
        )
    }


    @Test
    fun testParserOverflow() = runTest {
        try {
            eval(
                """
            fun Iterable.minByWithIndex( lambda ) {
            val i = iterator()
            var index = 0
            if( !i.hasNext() ) return null
            var value = i.next()
            var n = 1
            while( i.hasNext() ) {
                val x = lambda(i.next())
                if( x < value ) {
                    index = n
                    value = x
                }
                n++
            }
            index => value
        }
        """.trimIndent()
            )
            // If it compiles fine, great. The test passes implicitly.
        } catch (e: ScriptError) {
            // The important part: no StackOverflowError anymore, but a meaningful ScriptError
            // is thrown. Accept any ScriptError as a valid outcome.
            println(e.message)
        }
    }


//    @Test
//    fun namedArgsProposal() = runTest {
//        eval("""
//            import lyng.stdlib
//
//            fun test(a="a", b="b", c="c") { [a, b, c] }
//
//            val l = (test{ void }).last()
//            println(l)
//
//        """.trimIndent())
//    }


//    @Ignore
//    @Test
//    fun interpolationTest() = runTest {
//        eval($$$"""
//
//            val foo = "bar"
//            val buzz = ["foo", "bar"]
//
//            // 1. simple interpolation
//            assertEquals( "bar", "$foo" )
//            assertEquals( "bar", "${foo}" )
//
//            // 2. escaping the dollar sign
//            assertEquals( "$", "\$foo"[0] )
//            assertEquals( "foo, "\$foo"[1..] )
//
//            // 3. interpolation with expression
//            assertEquals( "foo!. bar?", "${buzz[0]+"!"}. ${buzz[1]+"?"}" )
//        """.trimIndent())
//    }

    @Test
    fun testInlineArrayLiteral() = runTest {
        eval(
            """
           val res = []
           for( i in [4,3,1] ) {
                res.add(i)
            }   
            assertEquals( [4,3,1], res )
       """.trimIndent()
        )
    }

    @Test
    fun testInlineMapLiteral() = runTest {
        eval(
            """
           val res = {}
           for( i in {foo: "bar"} ) {
                res[i.key] = i.value
            }   
            assertEquals( {foo: "bar"}, res )
       """.trimIndent()
        )
    }

    @Test
    fun testCommentsInClassConstructor() = runTest {
        eval(
            """
           class T(
               // comment 1
               val x: Int,
               // comment 2
               val y: String
           )
           
           println( T(1, "2") )
           
        """
        )
    }

    @Serializable
    data class JSTest1(val foo: String, val one: Int, val ok: Boolean)

    @Test
    fun testToJson() = runTest {
        val x = eval("""{ "foo": "bar", "one": 1, "ok": true }""")
        println(x.toJson())
        assertEquals(x.toJson().toString(), """{"foo":"bar","one":1,"ok":true}""")
        assertEquals(
            (eval("""{ "foo": "bar", "one": 1, "ok": true }.toJsonString()""") as ObjString).value,
            """{"foo":"bar","one":1,"ok":true}"""
        )
        println(x.decodeSerializable<JSTest1>())
        assertEquals(JSTest1("bar", 1, true), x.decodeSerializable<JSTest1>())
    }

    @Test
    fun testJsonTime() = runTest {
        val now = Clock.System.now()
        val x = eval(
            """
            import lyng.time
            Instant.now().truncateToSecond()
            """.trimIndent()
        ).decodeSerializable<Instant>()
        println(x)
        assertIs<Instant>(x)
        assertTrue((now - x).absoluteValue < 2.seconds)
    }

    @Test
    fun testJsonNull() = runTest {
        val x = eval("""null""".trimIndent()).decodeSerializable<Instant?>()
        println(x)
        assertNull(x)
    }

    @Test
    fun testInstanceVars() = runTest {
        var x = eval(
            """
            // in this case, x and y are constructor parameters, not instance variables:
            class T(x,y) {
                // and z is val and therefore needn't serialization either:
                val z = x + y
            }
            T(1, 2)
        """.trimIndent()
        ) as ObjInstance
        println(x.serializingVars.map { "${it.key}=${it.value.value}" })
        // so serializingVars is empty:
        assertEquals(emptyMap(), x.serializingVars)

        x = eval(
            """
            class T(x,y) {
                // variable z  though should be serialized:
                var z = x + y
            }
            val x = T(1, 2)
            x.z = 100
            assertEquals(100, x.z)
            x
        """.trimIndent()
        ) as ObjInstance
        // z is instance var, it must present
        val z = x.serializingVars["z"] ?: x.serializingVars["T::z"]
        // and be mutable:
        assertTrue(z!!.isMutable)
        println(x.serializingVars.map { "${it.key}=${it.value.value}" })
    }

    @Test
    fun memberValCantBeAssigned() = runTest {
        eval(
            """
            class Point(foo,bar) {
                val t = 42
                var r = 142
            }
            val p = Point(1,2)
            // val should not be assignable:
            assertThrows { p = Point(3,4) }
           
            // val field must be readonly:
            assertThrows { p.t = "bad" }
           
            p.r = 123
           
            // and the value should not be changed
            assertEqual(42, p.t)
            
            // but r should be changed:
            assertEqual(123, p.r)
        """
        )
    }

    @Test
    fun testClassToJson() = runTest {
        eval(
            """
            import lyng.serialization
            class Point(foo,bar) {
                val t = 42
            }
            // val is not serialized
            assertEquals( "{\"foo\":1,\"bar\":2}", Point(1,2).toJsonString() )
            class Point2(foo,bar) {
                var reason = 42
            }
            // var is serialized instead
            assertEquals( "{\"foo\":1,\"bar\":2,\"reason\":42}", Point2(1,2).toJsonString() )
        """.trimIndent()
        )

    }

    @Test
    fun testCustomClassToJson() = runTest {
        eval(
            """
            import lyng.serialization
           
            class Point2(foo,bar) {
                var reason = 42
                // but we override json serialization:
                fun toJsonObject() {
                    { "custom": true }
                }
            }
            // var is serialized instead
            assertEquals( "{\"custom\":true}", Point2(1,2).toJsonString() )
        """.trimIndent()
        )
    }

    @Serializable
    data class TestJson2(
        val value: Int,
        val inner: Map<String, Int>
    )

    @Test
    fun deserializeMapWithJsonTest() = runTest {
        val x = eval(
            """
            import lyng.serialization
            { value: 1, inner: { "foo": 1, "bar": 2 }}
        """.trimIndent()
        ).decodeSerializable<TestJson2>()
        assertEquals(TestJson2(1, mapOf("foo" to 1, "bar" to 2)), x)
    }

    @Serializable
    data class TestJson3(
        val value: Int,
        val inner: JsonObject
    )

    @Test
    fun deserializeAnyMapWithJsonTest() = runTest {
        val x = eval(
            """
            import lyng.serialization
            { value: 12, inner: { "foo": 1, "bar": "two" }}
        """.trimIndent()
        ).decodeSerializable<TestJson3>()
        assertEquals(
            TestJson3(
                12,
                JsonObject(mapOf("foo" to JsonPrimitive(1), "bar" to Json.encodeToJsonElement("two")))
            ), x
        )
    }

    @Serializable
    enum class TestEnum {
        One, Two
    }

    @Serializable
    data class TestJson4(val value: TestEnum)

    @Test
    fun deserializeEnumJsonTest() = runTest {
        val x = eval(
            """
            import lyng.serialization
            enum TestEnum { One, Two }
            { value: TestEnum.One }
        """.trimIndent()
        ).decodeSerializable<TestJson4>()
        assertEquals(TestJson4(TestEnum.One), x)
    }

    @Test
    fun testStringLast() = runTest {
        eval(
            """
            assertEquals('t', "assert".last())  
        """.trimIndent()
        )
    }

    @Test
    fun testStringMul() = runTest {
        eval("""
            assertEquals("hellohello", "hello"*2)
            assertEquals("", "hello"*0)
        """.trimIndent())
    }

    @Test
    fun testLogicalNot() = runTest {
        eval(
            """
            val vf = false 
            fun f() { false }
            assert( !false )
            assert( !vf )
            assert( !f() )
            
            val vt = true
            fun ft() { true }
            if( !true )
                throw "impossible"
                
            if( !ft() )
                throw "impossible"
                
            if( !vt )
                throw "impossible"
                
            // real world sample

            fun isSignedByAdmin() {
                // just ok
                true
            }
            
            fun requireAdmin() {
                // this caused compilation error:
                if( !isSignedByAdmin() )
                    throw "Admin signature required"
            }
        
        """.trimIndent()
        )
    }

    @Test
    fun testHangOnPrintlnInMethods() = runTest {
        eval(
            """
            class T(someList) {
                fun f() {
                    val x = [...someList]
                    println(x)
                }
            }
            T([1,2]).f()
        """
        )
    }

    @Test
    fun testHangOnNonexistingMethod() = runTest {
        eval(
            """
            class T(someList) {
                fun f() {
                    nonExistingMethod()
                }
            }
            val t = T([1,2])
            try {
            for( i in 1..10 ) {
                    t.f()
                }
            }
            catch(t: SymbolNotFound) {
                println(t::class)
                // ok
            }
        """
        )
    }

    @Test
    fun testUsingClassConstructorVars() = runTest {
        val r = eval(
            """
            import lyng.time
            
            class Request {
                static val id = "rqid"
            }
            enum Action { 
                Test
            }
            class LogEntry(vaultId, action, data=null, createdAt=Instant.now().truncateToSecond()) {

                /*
                Convert to a map object that can be easily decoded outsude the
                contract execution scope.
                */
                fun toApi() {
                    { createdAt:, requestId: Request.id, vaultId:, action: action.name, data: Map() }
                }
            }
            fun test() {
                LogEntry("v1", Action.Test).toApi()
            }
            
            test()
        """.trimIndent()
        ).toJson()
        println(r)
    }

    @Test
    fun testScopeShortCircuit() = runTest() {
        val baseScope = Script.newScope()

        baseScope.eval(
            """
                val exports = Map()
                fun Export(name,f) {
                    exports[name] = f
                    f
                }
        """.trimIndent()
        )

        val exports: MutableMap<Obj, Obj> = (baseScope.eval("exports") as ObjMap).map

        baseScope.eval(
            """
            class A(val a) {
                fun methodA() {
                    a + 1
                }
            }
            val a0 = 100
            
            fun someFunction(x) {
                val ia = A(x)
                ia.methodA()
            }            
            
            @Export
            fun exportedFunction(x) {
                someFunction(x)
            }
        """.trimIndent()
        )
        // Calling from the script is ok:
        val instanceScope = baseScope.createChildScope()
        instanceScope.eval(
            """
            val a1 = a0 + 1
        """.trimIndent()
        )
        assertEquals(
            ObjInt(2), instanceScope.eval(
                """
            exportedFunction(1)
        """
            )
        )
        assertEquals(
            ObjInt(103), instanceScope.eval(
                """
            exportedFunction(a1 + 1)
        """
            )
        )
        val dummyThis = Obj()
        // but we should be able to call it directly
        val otherScope = baseScope.createChildScope()
        val r = (exports["exportedFunction".toObj()] as Statement).invoke(otherScope, dummyThis, ObjInt(50))
        println(r)
        assertEquals(51, r.toInt())
    }

    @Test
    fun testFirstInEnum() = runTest {
        eval(
            """
            enum E {
                one, two, three 
            }
            println(E.entries)
            assertEquals( E.two, E.entries.findFirst { 
                println(it.name)
                it.name in ["aaa", "two"] 
            } ) 
            
        """.trimIndent()
        )
    }

    @Test
    fun testAutoSplatArgs() = runTest {
        eval(
            """
            fun tf(x, y, z) {
                "x=%s, y=%s, z=%s"(x,y,z)
            }
            assertEquals(tf(1, 2, 3), "x=1, y=2, z=3")
            val a = { x: 3, y: 4, z: 5 }
            assertEquals(tf(...a), "x=3, y=4, z=5")
            assertEquals(tf(...{ x: 3, y: 4, z: 50 }), "x=3, y=4, z=50")
        """.trimIndent()
        )
    }

    @Test
    fun testCached() = runTest {
        eval(
            """
            var counter = 0
            val f = cached { ++counter }
             
            assertEquals(1,f())
            assertEquals(1, counter)
            assertEquals(1,f())
            assertEquals(1, counter)
        """.trimIndent()
        )
    }

    @Test
    fun testCustomToStringBug() = runTest {
        eval(
            """
            class A(x,y)
            class B(x,y) {
                override fun toString() {
                    "B(%d,%d)"(x,y)
                }
            }
            
            assertEquals("B(1,2)", B(1,2).toString())
            assertEquals("A(x=1,y=2)", A(1,2).toString())
            
            // now tricky part: this _should_ call custom toString()
            assertEquals(":B(1,2)", ":" + B(1,2).toString())
            // and this must be exactly same:
            assertEquals(":B(1,2)", ":" + B(1,2))
            
        """.trimIndent()
        )

    }


    @Test
    fun testDestructuringAssignment() = runTest {
        eval(
            """
            val abc = [1, 2, 3]
            // plain:
            val [a, b, c] = abc
            assertEquals( 1, a )
            assertEquals( 2, b )
            assertEquals( 3, c )
            // with splats, receiving into list:
            val [ab..., c1] = abc
            assertEquals( [1, 2], ab )
            assertEquals( 3, c1 )
            // also in the end
            val [a1, rest...] = abc
            assertEquals( 1, a1 )
            assertEquals( [2, 3], rest )
            // and in the middle
            val [a_mid, middle..., e0, e1] = [ 1, 2, 3, 4, 5, 6]
            assertEquals( [2, 3, 4], middle )
            assertEquals( 5, e0 )
            assertEquals( 6, e1 )
            assertEquals( 1, a_mid )
            
            // nested destructuring:
            val [n1, [n2, n3...], n4] = [1, [2, 3, 4], 5]
            assertEquals(1, n1)
            assertEquals(2, n2)
            assertEquals([3, 4], n3)
            assertEquals(5, n4)

            // also it could be used to reassign vars:
            var x = 5
            var y = 10
            
            [x, y] = [y, x]
            assertEquals( 10, x )
            assertEquals( 5, y )
            
        """.trimIndent()
        )
    }

    @Test
    fun testProperlyReportExceptionPos() = runTest {
        var x = assertFailsWith<ExecutionError> {
            eval(
                """
            val tmp = 11
            
            if( tmp < 100 ) {
                throw "success"
            }
        """.trimIndent()
            )
        }
        println(x)
        assertEquals(3, x.pos.line)
        assertContains(x.message!!, "throw \"success\"")

        // comments shoudl not change reporting:
        x = assertFailsWith<ExecutionError> {
            eval(
                """
            val tmp = 11
            /* 
            This should not change position: 
            */
            fun test() {
                throw "success"
            }
            test()
            """.trimIndent()
            )
        }
        println(x)
        assertEquals(5, x.pos.line)
        assertContains(x.message!!, "throw \"success\"")
    }

    @Test
    fun testClassAndFunAutoNamedArgs() = runTest {
        // Shorthand for named arguments: name: is equivalent to name: name.
        // This is consistent with map literal shorthand in Lyng.
        eval(
            """
            fun test(a, b, c) {
                "%s-%s-%s"(a,b,c)
            }
            
            val a = 1
            val b = 2
            val c = 3
            
            // Basic usage:
            assertEquals( "1-2-3", test(a:, b:, c:) )
            assertEquals( "1-2-3", test(c:, b:, a:) )

            // Class constructors also support it:
            class Point(x, y) {
                val r = "x:%s, y:%s"(x, y)
            }
            val x = 10
            val y = 20
            assertEquals( "x:10, y:20", Point(x:, y:).r )
            assertEquals( "x:10, y:20", Point(y:, x:).r )
            
            // Mixed with positional arguments:
            assertEquals( "0-2-3", test(0, b:, c:) )
            
            // Mixed with regular named arguments:
            assertEquals( "1-99-3", test(a:, b: 99, c:) )
            
            // Integration with splats (spread arguments):
            val args = { b:, c: } // map literal shorthand
            assertEquals( "1-2-3", test(a:, ...args) )
            
            // Default values:
            fun sum(a, b=10, c=100) { a + b + c }
            assertEquals( 111, sum(a:) )
            assertEquals( 103, sum(a:, b:) )
            
            // Complex scenario with multiple splats and shorthands:
            val p1 = 1
            val p2 = 2
            val more = { c: 3, d: 4 }
            fun quad(a, b, c, d) { a + b + c + d }
            assertEquals( 10, quad(a: p1, b: p2, ...more) )
            
            """.trimIndent()
        )
    }

    @Test
    fun testFunMiniDeclaration() = runTest {
        eval("""
            class T(x) {
                fun method() = x + 1
            }
            fun median(a,b) = (a+b)/2
             
            assertEquals(11, T(10).method())
            assertEquals(2, median(1,3))
        """.trimIndent())
    }

    @Test
    fun testUserClassExceptions() = runTest {
        eval("""
            val x = try { throw IllegalAccessException("test1") } catch { it }
            assertEquals("test1", x.message)
            assert( x is IllegalAccessException)
            assert( x is Exception )
            assertThrows(IllegalAccessException) {   throw IllegalAccessException("test2") }

            class X : Exception("test3")
            val y = try { throw X() } catch { it }
            println(y)
            assertEquals("test3", y.message)
            assert( y is X)
            assert( y is Exception )

        """.trimIndent())
    }

    @Test
    fun testTodo() = runTest {
        eval("""
            assertThrows(NotImplementedException) {
                TODO()
            }
            val x = try { TODO("check me") } catch { it }
            assertEquals("check me", x.message)
        """.trimIndent())
    }

    @Test
    fun testOptOnNullAssignment() = runTest {
        eval("""
            var x = null
            assertEquals(null, x)
            x ?= 1
            assertEquals(1, x)
            x ?= 2
            assertEquals(1, x)
        """.trimIndent())
    }

    @Test
    fun testUserExceptionClass() = runTest {
        eval("""
            class UserException : Exception("user exception")
            val x = try { throw UserException() } catch { it }
            assertEquals("user exception", x.message)
            assert( x is UserException)
            val y = try { throw IllegalStateException() } catch { it }
            assert( y is IllegalStateException)
            
            // creating exceptions as usual objects:
            val z = IllegalArgumentException()
            assert( z is Exception )
            assert( z is IllegalArgumentException )
            
            class X : Exception
            val t = X()
            assert( t is X )
            assert( t is Exception )
            
        """.trimIndent())
    }

    @Test
    fun testExceptionToString() = runTest {
        eval("""
            class MyEx(m) : Exception(m)
            val e = MyEx("custom error")
            val s = e.toString()
            assert( s.startsWith("MyEx: custom error at ") )
            
            val e2 = try { throw e } catch { it }
            assert( e2 === e )
            assertEquals("custom error", e2.message)
        """.trimIndent())
    }
    @Test
    fun testAssertThrowsUserException() = runTest {
        eval("""
            class MyEx : Exception
            class DerivedEx : MyEx
            
            assertThrows(MyEx) { throw MyEx() }
            assertThrows(Exception) { throw MyEx() }
            assertThrows(MyEx) { throw DerivedEx() }
            
            val caught = try { 
                assertThrows(DerivedEx) { throw MyEx() } 
                null
            } catch { it }
            assert(caught != null)
            assertEquals("Expected DerivedEx, got MyEx", caught.message)
            assert(caught.message == "Expected DerivedEx, got MyEx")
        """.trimIndent())
    }

    @Test
    fun testRaiseAsError() = runTest {
        var x = evalNamed( "tc1","""
            IllegalArgumentException("test3")
        """.trimIndent())
        var x1 = try { x.raiseAsExecutionError() } catch(e: ExecutionError) { e }
        println(x1.message)
        assertTrue { "tc1:1" in x1.message!! }
        assertTrue { "test3" in x1.message!! }

        // With user exception classes it should be the same at top level:
        x = evalNamed("tc2","""
            class E: Exception("test4")
            E()
        """.trimIndent())
        x1 = try { x.raiseAsExecutionError() } catch(e: ExecutionError) { e }
        println(x1.message)
        assertContains(x1.message!!, "test4")
        // the reported error message should include proper trace, which must include
        // source name, in our case, is is "tc2":
        assertContains(x1.message!!, "tc2")
    }

    @Test
    fun testFilterStackTrace() = runTest {
        var x = try {
            evalNamed( "tc1","""
            fun f2() = throw IllegalArgumentException("test3")
            fun f1() = f2()
            f1()
        """.trimIndent())
            fail("this should throw")
        }
        catch(x: ExecutionError) {
            x
        }
        assertEquals("""
            tc1:1:12: test3
                at tc1:1:12: fun f2() = throw IllegalArgumentException("test3")
                at tc1:2:12: fun f1() = f2()
                at tc1:3:1: f1()
        """.trimIndent(),x.errorObject.getLyngExceptionMessageWithStackTrace())
    }


    @Test
    fun testLyngToKotlinExceptionHelpers() = runTest {
        var x = evalNamed( "tc1","""
            IllegalArgumentException("test3")
        """.trimIndent())
        assertEquals("""
            tc1:1:1: test3
                at tc1:1:1: IllegalArgumentException("test3")
            """.trimIndent(),
            x.getLyngExceptionMessageWithStackTrace()
        )
    }

    @Test
    fun testMapIteralAmbiguity() = runTest {
        eval("""
            val m = { a: 1, b: { foo: "bar" } }
            assertEquals(1, m["a"])
            assertEquals("bar", m["b"]["foo"])
            val bar = "foobar"
            val m2 = { a: 1, b: { bar: } }
            assert( m2["b"] is Map )
            assertEquals("foobar", m2["b"]["bar"])
        """.trimIndent())
    }

    @Test
    fun realWorldCaptureProblem() = runTest {
        eval("""
            // 61755f07-630c-4181-8d50-1b044d96e1f4
            class T {
                static var f1 = null
                static fun testCapture(name=null) {
                    run {
                        // I expect it will catch the 'name' from
                        // param? 
                        f1 = name
                    }
                }
            }
            assert(T.f1 == null)
            println("-- "+T.f1::class)
            println("-- "+T.f1)
            T.testCapture("foo")
            println("2- "+T.f1::class)
            println("2- "+T.f1)
            assert(T.f1 == "foo")
        """.trimIndent())
    }

    @Test
    fun testLazyLocals() = runTest() {
        eval("""
            class T {
                val x by lazy {
                    val c = "c"
                    c + "!"
                }
            }
            val t = T()
            assertEquals("c!", t.x)
            assertEquals("c!", t.x)
        """.trimIndent())
    }
    @Test
    fun testGetterLocals() = runTest() {
        eval("""
            class T {
                val x get() {
                    val c = "c"
                    c + "!"
                }
            }
            val t = T()
            assertEquals("c!", t.x)
            assertEquals("c!", t.x)
        """.trimIndent())
    }

    @Test
    fun testMethodLocals() = runTest() {
        eval("""
            class T {
                fun x() {
                    val c = "c"
                    c + "!"
                }
            }
            val t = T()
            assertEquals("c!", t.x())
            assertEquals("c!", t.x())
        """.trimIndent())
    }

    @Test
    fun testContrcuctorMagicIdBug() = runTest() {
        eval("""
            interface SomeI {
                abstract fun x()
            }
            class T(id): SomeI {
                override fun x() {
                    val c = id
                    c + "!"
                }
            }
            val t = T("c")
            assertEquals("c!", t.x())
            assertEquals("c!", t.x())
        """.trimIndent())
    }

    @Test
    fun testLambdaLocals() = runTest() {
        eval("""
            class T {
                val l = { x ->
                    val c = x + ":"
                    c + x
                }
            }
            assertEquals("r:r", T().l("r"))
        """.trimIndent())
    }

    @Test
    fun testTypedArgsWithInitializers() = runTest {
        eval("""
            fun f(a: String = "foo") = a + "!"
            fun g(a: String? = null) = a ?: "!!"
            assertEquals(f(), "foo!")
            assertEquals(g(), "!!")
            assertEquals(f("bar"), "bar!")
            class T(b: Int=42,c: String?=null) 
            assertEquals(42, T().b)
            assertEquals(null, T().c)
        """.trimIndent())
    }

    @Test
    fun testArgsPriorityWithSplash() = runTest {
        eval("""
            class A {
                val tags get() = ["foo"]
                
                fun f1(tags...) = tags
                
                fun f2(tags...) = f1(...tags)
            }
            assertEquals(["bar"], A().f2("bar"))   
        """)
    }

    @Test
    fun testClamp() = runTest {
        eval("""
            // Global clamp
            assertEquals(5, clamp(5, 0..10))
            assertEquals(0, clamp(-5, 0..10))
            assertEquals(10, clamp(15, 0..10))
            
            // Extension clamp
            assertEquals(5, 5.clamp(0..10))
            assertEquals(0, (-5).clamp(0..10))
            assertEquals(10, 15.clamp(0..10))
            
            // Exclusive range
            assertEquals(9, 15.clamp(0..<10))
            assertEquals(0, (-5).clamp(0..<10))
            
            // Open-ended range
            assertEquals(10, 15.clamp(..10))
            assertEquals(-5, (-5).clamp(..10))
            assertEquals(5, 5.clamp(0..))
            assertEquals(0, (-5).clamp(0..))
            
            // Character range
            assertEquals('e', 'e'.clamp('a'..'z'))
            assertEquals('a', ' '.clamp('a'..'z'))
            assertEquals('z', '}'.clamp('a'..'z'))
            assertEquals('y', '}'.clamp('a'..<'z'))
            
            // Real numbers (boundaries are inclusive in current impl for Real)
            assertEquals(5.5, 5.5.clamp(0.0..10.0))
            assertEquals(0.0, (-1.5).clamp(0.0..10.0))
            assertEquals(10.0, 15.5.clamp(0.0..10.0))
        """.trimIndent())
    }

    @Test
    fun testEmptySpreadList() = runTest {
        eval("""
            fun t(a, tags=[]) { [a, ...tags] }
            assertEquals( [1], t(1) )
        """.trimIndent())
    }

    @Test
    fun testForInIterableDisasm() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            fun type(x) {
                when(x) {
                    "42", 42 -> "answer to the great question"
                    is Real, is Int -> "number"
                    is String -> {
                        for( d in x ) {
                            if( d !in '0'..'9' ) 
                                break "unknown"
                        }
                        else "number"
                    }
                }
            }
        """.trimIndent())
        println("[DEBUG_LOG] type disasm:\n${scope.disassembleSymbol("type")}")
        val r1 = scope.eval("""type("12%")""")
        val r2 = scope.eval("""type("153")""")
        println("[DEBUG_LOG] type(\"12%\")=${r1.inspect(scope)}")
        println("[DEBUG_LOG] type(\"153\")=${r2.inspect(scope)}")
    }

    @Test
    fun testForInIterableBytecode() = runTest {
        val result = eval("""
            fun sumAll(x) {
                var s = 0
                for (i in x) s += i
                s
            }
            sumAll([1,2,3]) + sumAll(0..3)
        """.trimIndent())
        assertEquals(ObjInt(12), result)
    }

    @Test
    fun testForInIterableUnknownTypeDisasm() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            fun countAll(x) {
                var c = 0
                for (i in x) c++
                c
            }
        """.trimIndent())
        val disasm = scope.disassembleSymbol("countAll")
        println("[DEBUG_LOG] countAll disasm:\n$disasm")
        assertFalse(disasm.contains("not a compiled body"))
        assertFalse(disasm.contains("EVAL_FALLBACK"))
        val r1 = scope.eval("countAll([1,2,3])")
        val r2 = scope.eval("countAll(0..3)")
        assertEquals(ObjInt(3), r1)
        assertEquals(ObjInt(4), r2)
    }

    @Test
    fun testReturnBreakValueBytecodeDisasm() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            fun firstPositive() {
                for (i in 0..5)
                    if (i > 0) return i
                -1
            }

            fun firstEvenOrMinus() {
                val r = for (i in 1..7)
                    if (i % 2 == 0) break i
                r
            }
        """.trimIndent())
        val disasmReturn = scope.disassembleSymbol("firstPositive")
        val disasmBreak = scope.disassembleSymbol("firstEvenOrMinus")
        println("[DEBUG_LOG] firstPositive disasm:\n$disasmReturn")
        println("[DEBUG_LOG] firstEvenOrMinus disasm:\n$disasmBreak")
        assertFalse(disasmReturn.contains("not a compiled body"))
        assertFalse(disasmBreak.contains("not a compiled body"))
        assertFalse(disasmReturn.contains("EVAL_FALLBACK"))
        assertFalse(disasmBreak.contains("EVAL_FALLBACK"))
        assertEquals(ObjInt(1), scope.eval("firstPositive()"))
        assertEquals(ObjInt(2), scope.eval("firstEvenOrMinus()"))
    }
}
