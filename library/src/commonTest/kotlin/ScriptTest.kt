package io.github.kotlin.fibonacci

import kotlinx.coroutines.test.runTest
import net.sergeych.ling.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptTest {

    @Test
    fun level0() = runTest {
        val s = Script(
            Pos.builtIn,
            listOf(
                CallStatement(
                    Pos.builtIn, "println",
                    Arguments(listOf(CallStatement(Pos.builtIn, "π", Arguments.EMPTY)))
                )
            )
        )
        s.execute(basicContext)
    }

    fun parseFirst(str: String): Token =
        parseLing(str.toSource()).firstOrNull()!!

    @Test
    fun parseNumbers() {
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
    fun parse0() {
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
    fun parse1() {
        val src = "2 + 7".toSource()

        val p = parseLing(src).listIterator()

        assertEquals(Token("2", src.posAt(0, 0), Token.Type.INT), p.next())
        assertEquals(Token("+", src.posAt(0, 2), Token.Type.PLUS), p.next())
        assertEquals(Token("7", src.posAt(0, 4), Token.Type.INT), p.next())
    }

    @Test
    fun compileNumbers() = runTest {
        assertEquals(ObjInt(17), eval("17"))
        assertEquals(ObjInt(17), eval("+17"))
        assertEquals(ObjInt(-17), eval("-17"))


        assertEquals(ObjInt(1970), eval("1900 + 70"))
        assertEquals(ObjInt(1970), eval("2000 - 30"))

//        assertEquals(ObjReal(3.14), eval("3.14"))
        assertEquals(ObjReal(314.0), eval("3.14e2"))
    }

}