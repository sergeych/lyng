package io.github.kotlin.fibonacci

import kotlinx.coroutines.test.runTest
import net.sergeych.ling.*
import kotlin.math.PI
import kotlin.test.*

class ScriptTest {

    @Test
    fun parseNewlines() {
        fun check(expected: String, type: Token.Type, row: Int, col: Int, src: String, offset: Int = 0) {
            val source = src.toSource()
            assertEquals(
                Token(expected, source.posAt(row, col), type),
                parseLing(source)[offset]
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
                parseLing(source)[offset]
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
    fun parserLabelsTest() {
        val src = "label@ break@label".toSource()
        val tt = parseLing(src)
        assertEquals(Token("label", src.posAt(0, 0), Token.Type.LABEL), tt[0])
        assertEquals(Token("break", src.posAt(0, 7), Token.Type.ID), tt[1])
        assertEquals(Token("label", src.posAt(0, 12), Token.Type.ATLABEL), tt[2])
    }

    @Test
    fun parse0Test() {
        val src = """
            println("Hello")
            println( "world" )
        """.trimIndent().toSource()

        val p = parseLing(src).listIterator()

        assertEquals(Token("println", src.posAt(0, 0), Token.Type.ID), p.next())
        assertEquals(Token("(", src.posAt(0, 7), Token.Type.LPAREN), p.next())
        assertEquals(Token("Hello", src.posAt(0, 8), Token.Type.STRING), p.next())
        assertEquals(Token(")", src.posAt(0, 15), Token.Type.RPAREN), p.next())
        assertEquals(Token("\n", src.posAt(0, 16), Token.Type.NEWLINE), p.next())
        assertEquals(Token("println", src.posAt(1, 0), Token.Type.ID), p.next())
        assertEquals(Token("(", src.posAt(1, 7), Token.Type.LPAREN), p.next())
        assertEquals(Token("world", src.posAt(1, 9), Token.Type.STRING), p.next())
        assertEquals(Token(")", src.posAt(1, 17), Token.Type.RPAREN), p.next())
    }

    @Test
    fun parse1Test() {
        val src = "2 + 7".toSource()

        val p = parseLing(src).listIterator()

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
        val pi = eval("Math.PI")
        assertIs<ObjReal>(pi)
        assertTrue(pi.value - PI < 0.000001)
        assertTrue(eval("Math.PI+1").toDouble() - PI - 1.0 < 0.000001)

        assertTrue(eval("sin(Math.PI)").toDouble() - 1 < 0.000001)
        assertTrue(eval("sin(π)").toDouble() - 1 < 0.000001)
    }

    @Test
    fun varsAndConstsTest() = runTest {
        val context = Context(pos = Pos.builtIn)
        assertEquals(
            ObjVoid, context.eval(
                """
            val a = 17
            var b = 3
        """.trimIndent()
            )
        )
        assertEquals(17, context.eval("a").toInt())
        assertEquals(20, context.eval("b + a").toInt())
        assertFailsWith<ScriptError> {
            context.eval("a = 10")
        }
        assertEquals(17, context.eval("a").toInt())
        assertEquals(5, context.eval("b = a - 7 - 5").toInt())
        assertEquals(5, context.eval("b").toInt())
    }

    @Test
    fun functionTest() = runTest {
        val context = Context(pos = Pos.builtIn)
        context.eval(
            """
            fun foo(a, b) {
                a + b
            }
        """.trimIndent()
        )
        assertEquals(17, context.eval("foo(3,14)").toInt())
        assertFailsWith<ScriptError> {
            assertEquals(17, context.eval("foo(3)").toInt())
        }

        context.eval(
            """
            fn bar(a, b=10) {
                a + b + 1
            }
        """.trimIndent()
        )
        assertEquals(10, context.eval("bar(3, 6)").toInt())
        assertEquals(14, context.eval("bar(3)").toInt())
    }

    @Test
    fun simpleClosureTest() = runTest {
        val context = Context(pos = Pos.builtIn)
        context.eval(
            """
            var global = 10
            
            fun foo(a, b) {
                global + a + b
            }
        """.trimIndent()
        )
        assertEquals(27, context.eval("foo(3,14)").toInt())
        context.eval("global = 20")
        assertEquals(37, context.eval("foo(3,14)").toInt())
    }

    @Test
    fun nullAndVoidTest() = runTest {
        val context = Context(pos = Pos.builtIn)
        assertEquals(ObjVoid, context.eval("void"))
        assertEquals(ObjNull, context.eval("null"))
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
        assertEquals(ObjBool(false), eval("val x = 3; x == 2"))
        assertEquals(ObjBool(true), eval("val x = 3; x != 2"))
        assertEquals(ObjBool(false), eval("val x = 3; x != 3"))

        assertTrue { eval("1 == 1").toBool() }
        assertTrue { eval("true == true").toBool() }
        assertTrue { eval("true != false").toBool() }
        assertFalse { eval("true == false").toBool() }
        assertFalse { eval("false != false").toBool() }

        assertTrue { eval("2 == 2 && 3 != 4").toBool() }
    }

    @Test
    fun logicTest() = runTest {
        assertEquals(ObjBool(false), eval("true && false"))
        assertEquals(ObjBool(false), eval("false && false"))
        assertEquals(ObjBool(false), eval("false && true"))
        assertEquals(ObjBool(true), eval("true && true"))

        assertEquals(ObjBool(true), eval("true || false"))
        assertEquals(ObjBool(false), eval("false || false"))
        assertEquals(ObjBool(true), eval("false || true"))
        assertEquals(ObjBool(true), eval("true || true"))

        assertEquals(ObjBool(false), eval("!true"))
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
        var context = Context(pos = Pos.builtIn)
        context.eval(
            """
            fn test1(n) {
                var result = "more"
                if( n >= 10 ) 
                    result = "enough"
                result
            }
        """.trimIndent()
        )
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())

        // if - multiline (block)
        context = Context(pos = Pos.builtIn)
        context.eval(
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
        assertEquals("answer: enough", context.eval("test1(11)").toString())
        assertEquals("answer: more", context.eval("test1(1)").toString())

        // else single line1
        context = Context(pos = Pos.builtIn)
        context.eval(
            """
            fn test1(n) {
                if( n >= 10 )
                    "enough"
                else
                    "more"
            }
        """.trimIndent()
        )
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())

        // if/else with blocks
        context = Context(pos = Pos.builtIn)
        context.eval(
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
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())
        assertEquals("too much", context.eval("test1(100)").toString())
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
                println("starting t2 = " + t2)
                while( t2 > 0 ) {
                    t2 = t2 - 1
                    println("t2 " + t2 + " t1 " + t1)
                    if( t2 == 3 && t1 == 7) {
                        println("will break")
                        break@outer "ok2:"+t2+":"+t1
                    }
                }
                println("next t1")
                t1 = t1 - 1
                println("t1 now "+t1)
                t1
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
                println(count)
                println(res)
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
        val c = Context()
        c.eval("var x = 10")
        assertEquals(10, c.eval("x++").toInt())
        assertEquals(11, c.eval("x++").toInt())
        assertEquals(12, c.eval("x").toInt())

        assertEquals(12, c.eval("x").toInt())
        assertEquals(12, c.eval("x").toInt())
    }

    @Test
    fun testDecr() = runTest {
        val c = Context()
        c.eval("var x = 9")
        assertEquals(9, c.eval("x--").toInt())
        assertEquals(8, c.eval("x--").toInt())
        assertEquals(7, c.eval("x--").toInt())
        assertEquals(6, c.eval("x--").toInt())
        assertEquals(5, c.eval("x").toInt())
    }

    @Test
    fun testDecrIncr() = runTest {
        val c = Context()
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
        val c = Context()
        c.eval("var x = 9")
        assertEquals(9, c.eval("x--").toInt())
        assertEquals(8, c.eval("x--").toInt())
        assertEquals(7, c.eval("x--").toInt())
        assertEquals(6, c.eval("x").toInt())
        assertEquals(6, c.eval("x++").toInt())
        assertEquals(7, c.eval("x++").toInt())
        assertEquals(8, c.eval("x")
            .also {
                println("${it.toDouble()} ${it.toInt()} ${it.toLong()} ${it.toInt()}")
            }
            .toInt())
    }

    @Test
    fun testDecrIncr3() = runTest {
        val c = Context()
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
        val c = Context()
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
        val ctx = Context()
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
        val ctx = Context()
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
        val cxt = Context()
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
        eval("""
            val list = [1,22,3]
            assert(list[0] == 1)
            assert(list[1] == 22)
            assert(list[2] == 3)
        """.trimIndent())

        eval("""
            val x0 = 100
            val list = [x0 + 1, x0 * 10, 3]
            assert(list[0] == 101)
            assert(list[1] == 1000)
            assert(list[2] == 3)
        """.trimIndent())

        eval("""
            val x0 = 100
            val list = [x0 + 1, x0 * 10, if(x0 < 100) "low" else "high", 5]
            assert(list[0] == 101)
            assert(list[1] == 1000)
            assert(list[2] == "high")
            assert(list[3] == 5)
        """.trimIndent())

    }

    @Test
    fun testListLiteralSpread() = runTest {
        eval("""
            val list1 = [1,22,3]
            val list = ["start", ...list1, "end"]
            assert(list[0] == "start")
            assert(list[1] == 1)
            assert(list[2] == 22)
            assert(list[3] == 3)
            assert(list[4] == "end")
        """.trimIndent())
    }

    @Test
    fun testListSize() = runTest {
        eval("""
            val a = [4,3]
            assert(a.size == 2)
        """.trimIndent())
    }

//    @Test
//    fun testLambda1() = runTest {
//        val l = eval("""
//            x = {
//                122
//            }
//            x
//        """.trimIndent())
//        println(l)
//    }
//
}