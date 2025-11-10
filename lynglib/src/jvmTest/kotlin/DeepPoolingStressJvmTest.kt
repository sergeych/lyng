/*
 * JVM stress tests for scope frame pooling (deep nesting and recursion).
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepPoolingStressJvmTest {
    @Test
    fun deepNestedCalls_noLeak_and_correct_with_and_without_pooling() = runBlocking {
        val depth = 200
        val script = """
            fun f0(x) { x + 1 }
            fun f1(x) { f0(x) + 1 }
            fun f2(x) { f1(x) + 1 }
            fun f3(x) { f2(x) + 1 }
            fun f4(x) { f3(x) + 1 }
            fun f5(x) { f4(x) + 1 }

            fun chain(x, d) {
                var i = 0
                var s = x
                while (i < d) {
                    // 5 nested calls per iteration
                    s = f5(s)
                    i = i + 1
                }
                s
            }
            chain(0, $depth)
        """.trimIndent()

        // Pool OFF
        PerfFlags.SCOPE_POOL = false
        val scope1 = Scope()
        val r1 = (scope1.eval(script) as ObjInt).value
        // Pool ON
        PerfFlags.SCOPE_POOL = true
        val scope2 = Scope()
        val r2 = (scope2.eval(script) as ObjInt).value
        // Each loop adds 6 (f0..f5 adds 6)
        val expected = 6L * depth
        assertEquals(expected, r1)
        assertEquals(expected, r2)
        // Reset
        PerfFlags.SCOPE_POOL = false
    }

    @Test
    fun recursion_factorial_correct_with_and_without_pooling() = runBlocking {
        val n = 10
        val script = """
            fun fact(x) {
                if (x <= 1) 1 else x * fact(x - 1)
            }
            fact($n)
        """.trimIndent()
        // OFF
        PerfFlags.SCOPE_POOL = false
        val scope1 = Scope()
        val r1 = (scope1.eval(script) as ObjInt).value
        // ON
        PerfFlags.SCOPE_POOL = true
        val scope2 = Scope()
        val r2 = (scope2.eval(script) as ObjInt).value
        // 10! = 3628800
        val expected = 3628800L
        assertEquals(expected, r1)
        assertEquals(expected, r2)
        PerfFlags.SCOPE_POOL = false
    }
}
