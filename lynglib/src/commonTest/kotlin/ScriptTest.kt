import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.*
import net.sergeych.lyng.pacman.InlineSourcesImportProvider
import kotlin.test.*

class ScriptTest {

    @Test
    fun testVersion() {
        println("--------------------------------------------")
        println("version = ${LyngVersion}")
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
        assertEquals(9, c.eval("x--").toInt())
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
        assertEquals(8, c.eval("x")
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
        assertEquals("1.0E-6", eval("1e-6").toString())
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
            assertEquals("e=[1, 2]f=xx", f(1,2) { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun testMethodCallLastBlockAfterDefault() = runTest {
        eval(
            """
            class Foo {
                // this means last is lambda:
                fun f(e=1, f) {
                    "e="+e+"f="+f()
                }
            }
            val f = Foo()
            assertEquals("e=1f=xx", f.f { "xx" })
        """.trimIndent()
        )

    }

    @Test
    fun testMethodCallLastBlockWithEllipsis() = runTest {
        eval(
            """
            class Foo {
                // this means last is lambda:
                fun f(e..., f) {
                    "e="+e+"f="+f()
                }
            }
            val f = Foo()
            assertEquals("e=[]f=xx", f.f { "xx" })
            assertEquals("e=[1, 2]f=xx", f.f(1,2) { "xx" })
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
        val c = Scope()
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
            assert(set.toList() == [1,2,3,4])
            assert(set == Set(1,2,3,4))
            
            val s1 = [1, 2].toSet()
            assertEquals( Set(1,2), s1 * set) 
            
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
            >>> void

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
                this.x++; this.y++ 
            }
            assertEquals(p, Point(2,3))
            >>> void

        """.trimIndent()
        )
    }

    @Test
    fun testExtend() = runTest() {
        eval(
            """
            
            fun Int.isEven() {
                this % 2 == 0
            }
            
            fun Object.isInteger() {
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
            assert( ! "5.2".isInteger() )
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
        val scope = Scope()
        assertFails {
            scope.eval("""
                import foo
                foo()
                """.trimIndent())
        }
        scope.importManager.addTextPackages("""
            package foo
            
            fun foo() { "bar" }
        """.trimIndent())
        scope.eval("""
                import foo
                assertEquals( "bar", foo())
                """.trimIndent())
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
    fun testBuffer() = runTest {
        eval("""
            import lyng.buffer
            
            assertEquals( 0, Buffer().size )
            assertEquals( 3, Buffer(1, 2, 3).size )
            assertEquals( 5, Buffer("hello").size )
            
            val buffer = Buffer("Hello")
            assertEquals( 5, buffer.size)
            assertEquals('l'.code, buffer[2] )
            assertEquals('l'.code, buffer[3] )
            assertEquals("Hello", buffer.decodeUtf8())
            
            buffer[2] = 101
            assertEquals(101, buffer[2])
            assertEquals("Heelo", buffer.decodeUtf8())
            
        """.trimIndent())
    }

    @Test
    fun testBufferCompare() = runTest {
        eval("""
            import lyng.buffer
            
            println("Hello".characters())
            val b1 = Buffer("Hello")
            val b2 = Buffer("Hello".characters())
            
            assertEquals( b1, b2 )
            val b3 = b1 + Buffer("!")
            assertEquals( "Hello!", b3.decodeUtf8())
            assert( b3 > b1 )
            
        """.trimIndent())
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

}