/*
 * JVM micro-benchmarks for function/method call overhead and argument building.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CallBenchmarkTest {
    @Test
    fun benchmarkSimpleFunctionCalls() = runBlocking {
        val n = 300_000 // keep it fast for CI

        // A tiny script with 0, 1, 2 arg functions and a loop using them
        val script = """
            fun f0() { 1 }
            fun f1(a) { a }
            fun f2(a,b) { a + b }

            var s = 0
            var i = 0
            while (i < $n) {
                s = s + f0()
                s = s + f1(1)
                s = s + f2(1, 1)
                i = i + 1
            }
            s
        """.trimIndent()

        // Disable ARG_BUILDER for baseline
        PerfFlags.ARG_BUILDER = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] calls x$n [ARG_BUILDER=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Enable ARG_BUILDER for optimized run
        PerfFlags.ARG_BUILDER = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] calls x$n [ARG_BUILDER=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Correctness: each loop adds 1 + 1 + (1+1) = 4
        val expected = 4L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
