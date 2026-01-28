import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.eval
import net.sergeych.lyng.obj.toInt
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Ignore("TODO(bytecode-only): uses fallback")
class ReturnStatementTest {

    @Test
    fun testBasicReturn() = runTest {
        assertEquals(10, eval("""
            fun foo() {
                return 10
                20
            }
            foo()
        """).toInt())
    }

    @Test
    fun testReturnFromIf() = runTest {
        assertEquals(5, eval("""
            fun foo(x) {
                if (x > 0) return 5
                10
            }
            foo(1)
        """).toInt())
        
        assertEquals(10, eval("""
            fun foo(x) {
                if (x > 0) return 5
                10
            }
            foo(-1)
        """).toInt())
    }

    @Test
    fun testReturnFromLambda() = runTest {
        assertEquals(2, eval("""
            val f = { x ->
                if (x < 0) return 0
                x * 2
            }
            f(1)
        """).toInt())
        
        assertEquals(0, eval("""
            val f = { x ->
                if (x < 0) return 0
                x * 2
            }
            f(-1)
        """).toInt())
    }

    @Test
    fun testNonLocalReturn() = runTest {
        assertEquals(100, eval("""
            fun outer() {
                [1, 2, 3].forEach {
                    if (it == 2) return@outer 100
                }
                0
            }
            outer()
        """).toInt())
    }

    @Test
    fun testLabeledLambdaReturn() = runTest {
        assertEquals(42, eval("""
            val f = @inner { x ->
                if (x == 0) return@inner 42
                x
            }
            f(0)
        """).toInt())
        
        assertEquals(5, eval("""
            val f = @inner { x ->
                if (x == 0) return@inner 42
                x
            }
            f(5)
        """).toInt())
    }

    @Test
    fun testForbidEqualReturn() = runTest {
        assertFailsWith<ScriptError> {
            eval("fun foo(x) = return x")
        }
    }

    @Test
    fun testDeepNestedReturn() = runTest {
        assertEquals(42, eval("""
            fun find() {
                val data = [[1, 2], [3, 42], [5, 6]]
                data.forEach { row ->
                    row.forEach { item ->
                        if (item == 42) return@find item
                    }
                }
                0
            }
            find()
        """).toInt())
    }

    @Test
    fun testReturnFromOuterLambda() = runTest {
        assertEquals("found", eval("""
            val f_outer = @outer {
                val f_inner = {
                    return@outer "found"
                }
                f_inner()
                "not found"
            }
            f_outer()
        """).toString())
    }
}
