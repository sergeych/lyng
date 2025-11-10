/*
 * JVM micro-benchmark for expression evaluation with RVAL_FASTPATH.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionBenchmarkTest {
    @Test
    fun benchmarkExpressionChains() = runBlocking {
        val n = 350_000
        val script = """
            // arithmetic + elvis + logical chains
            val maybe = null
            var s = 0
            var i = 0
            while (i < $n) {
                // exercise elvis on a null
                s = s + (maybe ?: 0)
                // branch using booleans without coercion to int
                if ((i % 3 == 0 && true) || false) { s = s + 1 } else { s = s + 2 }
                // parity via arithmetic only (avoid adding booleans)
                s = s + (i - (i / 2) * 2)
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] expr-chain x$n [RVAL_FASTPATH=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.RVAL_FASTPATH = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] expr-chain x$n [RVAL_FASTPATH=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // correctness: compute expected with simple kotlin logic mirroring the loop
        var s = 0L
        var i = 0
        while (i < n) {
            if ((i % 3 == 0 && true) || false) s += 1 else s += 2
            // parity via arithmetic only, matches script's single parity addition
            s += i - (i / 2) * 2
            i += 1
        }
        assertEquals(s, r1)
        assertEquals(s, r2)
    }
}
