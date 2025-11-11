/*
 * JVM micro-benchmark for range for-in lowering under PRIMITIVE_FASTOPS.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class RangeBenchmarkTest {
    @Test
    fun benchmarkIntRangeForIn() = runBlocking {
        val n = 5_000 // outer repetitions
        val script = """
            var s = 0
            var i = 0
            while (i < $n) {
                // Hot inner counted loop over int range
                for (x in 0..999) { s = s + x }
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.PRIMITIVE_FASTOPS = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] range-for-in x$n (inner 0..999) [PRIMITIVE_FASTOPS=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.PRIMITIVE_FASTOPS = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] range-for-in x$n (inner 0..999) [PRIMITIVE_FASTOPS=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Each inner loop sums 0..999 => 999*1000/2 = 499500; repeated n times
        val expected = 499_500L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
