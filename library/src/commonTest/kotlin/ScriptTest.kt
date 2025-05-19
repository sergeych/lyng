package io.github.kotlin.fibonacci

import kotlinx.coroutines.test.runTest
import net.sergeych.ling.*
import kotlin.math.PI
import kotlin.test.*

class ScriptTest {

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
        val context = Context()
        assertEquals(ObjVoid,context.eval("""
            val a = 17
            var b = 3
        """.trimIndent()))
        assertEquals(17, context.eval("a").toInt())
        assertEquals(20, context.eval("b + a").toInt())
        assertFailsWith<ScriptError> {
            context.eval("a = 10")
        }
        assertEquals(10, context.eval("b = a - 3 - 4; b").toInt())
        assertEquals(10, context.eval("b").toInt())
    }

    @Test
    fun functionTest() = runTest {
        val context = Context()
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

        context.eval("""
            fn bar(a, b=10) {
                a + b + 1
            }
        """.trimIndent())
        assertEquals(10, context.eval("bar(3, 6)").toInt())
        assertEquals(14, context.eval("bar(3)").toInt())
    }

    @Test
    fun simpleClosureTest() = runTest {
        val context = Context()
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
        val context = Context()
        assertEquals(ObjVoid,context.eval("void"))
        assertEquals(ObjNull,context.eval("null"))
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
    fun arithmeticParenthesisTest() = runTest {
        assertEquals(17, eval("2 + 3 * 5").toInt())
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
    fun gtLtTest() = runTest {
        assertTrue { eval("3 > 2").toBool() }
        assertFalse { eval("3 > 3").toBool() }
        assertTrue { eval("3 >= 2").toBool() }
        assertFalse { eval("3 >= 4").toBool() }
        assertFalse { eval("3 < 2").toBool() }
        assertFalse { eval("3 <= 2").toBool() }
        assertTrue { eval("3 <= 3").toBool()}
        assertTrue { eval("3 <= 4").toBool()}
        assertTrue { eval("3 < 4").toBool()}
        assertFalse { eval("4 < 3").toBool()}
        assertFalse { eval("4 <= 3").toBool()}
    }

    @Test
    fun ifTest() = runTest {
        // if - single line
        var context = Context()
        context.eval("""
            fn test1(n) {
                var result = "more"
                if( n >= 10 ) 
                    result = "enough"
                result
            }
        """.trimIndent())
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())

        // if - multiline (block)
        context = Context()
        context.eval("""
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
        """.trimIndent())
        assertEquals("answer: enough", context.eval("test1(11)").toString())
        assertEquals("answer: more", context.eval("test1(1)").toString())

        // else single line1
        context = Context()
        context.eval("""
            fn test1(n) {
                if( n >= 10 ) 
                    "enough"
                else
                    "more"
            }
        """.trimIndent())
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())

        // if/else with blocks
        context = Context()
        context.eval("""
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
        """.trimIndent())
        assertEquals("enough", context.eval("test1(11)").toString())
        assertEquals("more", context.eval("test1(1)").toString())
        assertEquals("too much", context.eval("test1(100)").toString())
    }

}