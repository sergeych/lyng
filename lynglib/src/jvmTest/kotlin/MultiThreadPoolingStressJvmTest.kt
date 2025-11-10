/*
 * Multithreaded stress tests for ScopePool on JVM.
 */

import kotlinx.coroutines.*
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiThreadPoolingStressJvmTest {

    private suspend fun parallelEval(workers: Int, block: suspend (Int) -> Long): List<Long> = coroutineScope {
        (0 until workers).map { w -> async { block(w) } }.awaitAll()
    }

    @Test
    fun parallel_shallow_calls_correct_off_on() = runBlocking {
        val cpu = Runtime.getRuntime().availableProcessors()
        val workers = min(max(2, cpu), 8)
        val iterations = 25_000 // keep CI reasonable
        val script = """
            fun f0(a){ a }
            fun f1(a,b){ a + b }
            fun f2(a,b,c){ a + b + c }
            var s = 0
            var i = 0
            while(i < $iterations){
                s = s + f0(1)
                s = s + f1(1,1)
                s = s + f2(1,1,1)
                i = i + 1
            }
            s
        """.trimIndent()

        fun expected() = (1 + 2 + 3).toLong() * iterations

        // OFF
        PerfFlags.SCOPE_POOL = false
        val offResults = withContext(Dispatchers.Default) {
            parallelEval(workers) {
                val r = (Scope().eval(script) as ObjInt).value
                r
            }
        }
        // ON
        PerfFlags.SCOPE_POOL = true
        val onResults = withContext(Dispatchers.Default) {
            parallelEval(workers) {
                val r = (Scope().eval(script) as ObjInt).value
                r
            }
        }
        // reset
        PerfFlags.SCOPE_POOL = false

        val exp = expected()
        offResults.forEach { assertEquals(exp, it) }
        onResults.forEach { assertEquals(exp, it) }
    }

    @Test
    fun parallel_recursion_correct_off_on() = runBlocking {
        val cpu = Runtime.getRuntime().availableProcessors()
        val workers = min(max(2, cpu), 8)
        val depth = 12
        val script = """
            fun fact(x){ if(x <= 1) 1 else x * fact(x-1) }
            fact($depth)
        """.trimIndent()
        val expected = (1..depth).fold(1L){a,b->a*b}

        // OFF
        PerfFlags.SCOPE_POOL = false
        val offResults = withContext(Dispatchers.Default) {
            parallelEval(workers) {
                (Scope().eval(script) as ObjInt).value
            }
        }
        // ON
        PerfFlags.SCOPE_POOL = true
        val onResults = withContext(Dispatchers.Default) {
            parallelEval(workers) {
                (Scope().eval(script) as ObjInt).value
            }
        }
        // reset
        PerfFlags.SCOPE_POOL = false

        offResults.forEach { assertEquals(expected, it) }
        onResults.forEach { assertEquals(expected, it) }
    }
}
