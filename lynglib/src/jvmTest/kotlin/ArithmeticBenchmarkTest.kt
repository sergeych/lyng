/*
 * JVM micro-benchmarks for primitive arithmetic and comparison fast paths.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ArithmeticBenchmarkTest {
    @Test
    fun benchmarkIntArithmeticAndComparisons() = runBlocking {
        val n = 400_000
        val sumScript = """
            var s = 0
            var i = 0
            while (i < $n) {
                s = s + i
                i = i + 1
            }
            s
        """.trimIndent()

        // Baseline: disable primitive fast ops
        PerfFlags.PRIMITIVE_FASTOPS = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(sumScript) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] int-sum x$n [PRIMITIVE_FASTOPS=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized
        PerfFlags.PRIMITIVE_FASTOPS = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(sumScript) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] int-sum x$n [PRIMITIVE_FASTOPS=ON]: ${(t3 - t2)/1_000_000.0} ms")

        val expected = (n.toLong() - 1L) * n / 2L
        assertEquals(expected, r1)
        assertEquals(expected, r2)

        // Comparison heavy (branchy) loop
        val cmpScript = """
            var s = 0
            var i = 0
            while (i < $n) {
                if (i % 2 == 0) s = s + 1 else s = s + 2
                i = i + 1
            }
            s
        """.trimIndent()

        PerfFlags.PRIMITIVE_FASTOPS = false
        val scope3 = Scope()
        val t4 = System.nanoTime()
        val c1 = (scope3.eval(cmpScript) as ObjInt).value
        val t5 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] int-cmp x$n [PRIMITIVE_FASTOPS=OFF]: ${(t5 - t4)/1_000_000.0} ms")

        PerfFlags.PRIMITIVE_FASTOPS = true
        val scope4 = Scope()
        val t6 = System.nanoTime()
        val c2 = (scope4.eval(cmpScript) as ObjInt).value
        val t7 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] int-cmp x$n [PRIMITIVE_FASTOPS=ON]: ${(t7 - t6)/1_000_000.0} ms")

        // Expected: half of n even add 1, half odd add 2 (n even assumed)
        val expectedCmp = (n / 2) * 1L + (n - n / 2) * 2L
        assertEquals(expectedCmp, c1)
        assertEquals(expectedCmp, c2)
    }
}
