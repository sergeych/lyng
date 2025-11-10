/*
 * JVM micro-benchmark for mixed-arity function calls and ARG_BUILDER.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CallMixedArityBenchmarkTest {
    @Test
    fun benchmarkMixedArityCalls() = runBlocking {
        val n = 200_000
        val script = """
            fun f0() { 1 }
            fun f1(a) { a }
            fun f2(a,b) { a + b }
            fun f3(a,b,c) { a + b + c }
            fun f4(a,b,c,d) { a + b + c + d }

            var s = 0
            var i = 0
            while (i < $n) {
                s = s + f0()
                s = s + f1(1)
                s = s + f2(1, 1)
                s = s + f3(1, 1, 1)
                s = s + f4(1, 1, 1, 1)
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
        println("[DEBUG_LOG] [BENCH] mixed-arity x$n [ARG_BUILDER=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized
        PerfFlags.ARG_BUILDER = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] mixed-arity x$n [ARG_BUILDER=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Each loop: 1 + 1 + 2 + 3 + 4 = 11
        val expected = 11L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
