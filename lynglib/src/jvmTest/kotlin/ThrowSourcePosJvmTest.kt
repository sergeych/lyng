/*
 * Copyright 2025 Sergey S. Chernov
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScriptError
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ThrowSourcePosJvmTest {

    private fun assertThrowLine(code: String, expectedLine: Int) {
        try {
            runBlocking { Scope().eval(code) }
            fail("Expected ScriptError to be thrown, but nothing was thrown")
        } catch (se: ScriptError) {
            println(se.message)
            assertEquals(expectedLine, se.pos.line)
        }
    }

    @Test
    fun simpleThrow_afterComments_reportsCorrectLine() {
        val code = """
            // line 1
            // line 2
            throw "simple"
        """.trimIndent()
        // zero-based line index
        assertThrowLine(code, 2)
    }

    @Test
    fun inlineThrow_withLeadingSpaces_reportsCorrectLine() {
        val code = """
            val x = 1
                throw "boom"
        """.trimIndent()
        // throw is on the 2nd line (zero-based index 1)
        assertThrowLine(code, 1)
    }

    @Test
    fun throwInsideBlock_reportsCorrectLine() {
        val code = """
            if( true ) {
                // comment
                throw "boom"
            }
        """.trimIndent()
        // throw is on the 3rd line of the snippet (zero-based index 2)
        assertThrowLine(code, 2)
    }

    @Test
    fun throwAsExpression_reportsCorrectLine() {
        val code = """
            val x = null
            val y = x ?: throw "npe-like"
        """.trimIndent()
        // throw is on the 2nd line (zero-based index 1)
        assertThrowLine(code, 1)
    }
}
