/*
 * JVM micro-benchmark focused on local variable access paths:
 * - LOCAL_SLOT_PIC (per-frame slot PIC in LocalVarRef)
 * - EMIT_FAST_LOCAL_REFS (compiler-emitted fast locals)
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalVarBenchmarkTest {
    @Test
    fun benchmarkLocalReadsWrites_off_on() = runBlocking {
        val iterations = 400_000
        val script = """
            fun hot(n){
                var a = 0
                var b = 1
                var c = 2
                var s = 0
                var i = 0
                while(i < n){
                    a = a + 1
                    b = b + a
                    c = c + b
                    s = s + a + b + c
                    i = i + 1
                }
                s
            }
            hot($iterations)
        """.trimIndent()

        // Baseline: disable both fast paths
        PerfFlags.LOCAL_SLOT_PIC = false
        PerfFlags.EMIT_FAST_LOCAL_REFS = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] locals x$iterations [PIC=OFF, FAST_LOCAL=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized: enable both
        PerfFlags.LOCAL_SLOT_PIC = true
        PerfFlags.EMIT_FAST_LOCAL_REFS = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] locals x$iterations [PIC=ON, FAST_LOCAL=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Correctness: both runs produce the same result
        assertEquals(r1, r2)
    }
}
