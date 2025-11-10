/*
 * JVM micro-benchmark for calls with splat (spread) arguments.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CallSplatBenchmarkTest {
    @Test
    fun benchmarkCallsWithSplatArgs() = runBlocking {
        val n = 120_000
        val script = """
            fun sum4(a,b,c,d) { a + b + c + d }
            val base = [1,1,1,1]
            var s = 0
            var i = 0
            while (i < $n) {
                // two direct, one splat per iteration
                s = s + sum4(1,1,1,1)
                s = s + sum4(1,1,1,1)
                s = s + sum4(base[0], base[1], base[2], base[3])
                i = i + 1
            }
            s
        """.trimIndent()

        // Baseline
        PerfFlags.ARG_BUILDER = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] splat-calls x$n [ARG_BUILDER=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized
        PerfFlags.ARG_BUILDER = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] splat-calls x$n [ARG_BUILDER=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Each loop adds (4 + 4 + 4) = 12
        val expected = 12L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)

        // Reset to default
        PerfFlags.ARG_BUILDER = true
    }
}
