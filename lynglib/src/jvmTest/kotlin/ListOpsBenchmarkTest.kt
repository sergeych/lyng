/*
 * JVM micro-benchmark for list operations specialization under PRIMITIVE_FASTOPS.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ListOpsBenchmarkTest {
    @Test
    fun benchmarkSumInts() = runBlocking {
        val n = 200_000
        val script = """
            val list = (1..10).toList()
            var s = 0
            var i = 0
            while (i < $n) {
                // list.sum() should return 55 for [1..10]
                s = s + list.sum()
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
        println("[DEBUG_LOG] [BENCH] list-sum x$n [PRIMITIVE_FASTOPS=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.PRIMITIVE_FASTOPS = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] list-sum x$n [PRIMITIVE_FASTOPS=ON]: ${(t3 - t2)/1_000_000.0} ms")

        val expected = 55L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }

    @Test
    fun benchmarkContainsInts() = runBlocking {
        val n = 1_000_000
        val script = """
            val list = (1..10).toList()
            var s = 0
            var i = 0
            while (i < $n) {
                if (7 in list) { s = s + 1 }
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
        println("[DEBUG_LOG] [BENCH] list-contains x$n [PRIMITIVE_FASTOPS=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.PRIMITIVE_FASTOPS = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] list-contains x$n [PRIMITIVE_FASTOPS=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // 7 in [1..10] is always true
        val expected = 1L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
