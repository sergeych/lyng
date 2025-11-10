/*
 * Tiny JVM benchmark for local variable access performance.
 */

// import net.sergeych.tools.bm
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalVarBenchmarkTest {
    @Test
    fun benchmarkLocalVarLoop() = runBlocking {
        val n = 400_000 // keep under 1s even on CI
        val code = """
            var s = 0
            var i = 0
            while(i < $n) {
                s = s + i
                i = i + 1
            }
            s
        """.trimIndent()

        // Part 1: PIC off vs on for LocalVarRef
        PerfFlags.EMIT_FAST_LOCAL_REFS = false

        // Baseline: disable PIC
        PerfFlags.LOCAL_SLOT_PIC = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val result1 = (scope1.eval(code) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] local-var loop $n iters [baseline PIC=OFF, EMIT=OFF]: ${(t1 - t0) / 1_000_000.0} ms")

        // Optimized: enable PIC
        PerfFlags.LOCAL_SLOT_PIC = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val result2 = (scope2.eval(code) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] local-var loop $n iters [baseline PIC=ON, EMIT=OFF]: ${(t3 - t2) / 1_000_000.0} ms")

        // Verify correctness to avoid dead code elimination in future optimizations
        val expected = (n.toLong() - 1L) * n / 2L
        assertEquals(expected, result1)
        assertEquals(expected, result2)

        // Part 2: Enable compiler fast locals emission and measure
        PerfFlags.EMIT_FAST_LOCAL_REFS = true
        PerfFlags.LOCAL_SLOT_PIC = true

        val code2 = """
            fun sumN(n) {
                var s = 0
                var i = 0
                while(i < n) {
                    s = s + i
                    i = i + 1
                }
                s
            }
            sumN($n)
        """.trimIndent()

        val scope3 = Scope()
        val t4 = System.nanoTime()
        val result3 = (scope3.eval(code2) as ObjInt).value
        val t5 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] local-var loop $n iters [EMIT=ON]: ${(t5 - t4) / 1_000_000.0} ms")

        assertEquals(expected, result3)
    }
}
