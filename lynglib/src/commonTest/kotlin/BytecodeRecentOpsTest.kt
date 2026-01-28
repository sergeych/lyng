import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

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
                var x = 1
                fun add(n) { x += n }
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
}
