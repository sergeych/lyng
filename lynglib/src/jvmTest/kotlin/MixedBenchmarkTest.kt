/*
 * JVM mixed workload micro-benchmark to exercise multiple hot paths together.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class MixedBenchmarkTest {
    @Test
    fun benchmarkMixedWorkloadRvalFastpath() = runBlocking {
        // Keep iterations moderate to avoid CI timeouts
        val n = 250_000
        val script = """
            class Acc() {
                var x = 0
                fun add(v) { x = x + v }
                fun get() { x }
            }
            val acc = Acc()
            val maybe = null
            var s = 0
            var i = 0
            while (i < $n) {
                // exercise locals + primitive ops
                s = s + i
                // elvis on null
                s = s + (maybe ?: 0)
                // boolean logic (short-circuit + primitive fast path)
                if ((i % 3 == 0 && true) || false) { s = s + 1 } else { s = s + 2 }
                // instance field/method with PIC
                acc.add(1)
                // simple index with list building every 1024 steps (rare path)
                if (i % 1024 == 0) {
                    val lst = [0,1,2,3]
                    s = s + lst[2]
                }
                i = i + 1
            }
            s + acc.get()
        """.trimIndent()

        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] mixed x$n [RVAL_FASTPATH=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.RVAL_FASTPATH = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] mixed x$n [RVAL_FASTPATH=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Compute expected value in Kotlin to ensure correctness
        var s = 0L
        var i = 0
        var acc = 0L
        while (i < n) {
            s += i
            s += 0 // (maybe ?: 0)
            if ((i % 3 == 0 && true) || false) s += 1 else s += 2
            acc += 1
            if (i % 1024 == 0) s += 2
            i += 1
        }
        val expected = s + acc
        assertEquals(expected, r1)
        assertEquals(expected, r2)

        // Reset flag for other tests
        PerfFlags.RVAL_FASTPATH = false
    }
}
