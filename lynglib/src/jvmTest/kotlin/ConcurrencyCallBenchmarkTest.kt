/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
 * Multithreaded benchmark to quantify SCOPE_POOL speedup on JVM.
 */

import kotlinx.coroutines.*
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrencyCallBenchmarkTest {

    private suspend fun parallelEval(workers: Int, script: String): List<Long> = coroutineScope {
        (0 until workers).map { async { (Scope().eval(script) as ObjInt).value } }.awaitAll()
    }

    @Test
    fun benchmark_multithread_calls_off_on() = runBlocking {
        val cpu = Runtime.getRuntime().availableProcessors()
        val workers = min(max(2, cpu), 8)
        val iterations = 15_000 // per worker; keep CI fast
        val script = """
            fun f0() { 1 }
            fun f1(a) { a }
            fun f2(a,b) { a + b }
            fun f3(a,b,c) { a + b + c }
            fun f4(a,b,c,d) { a + b + c + d }
            var s = 0
            var i = 0
            while (i < $iterations) {
                s = s + f0()
                s = s + f1(1)
                s = s + f2(1, 1)
                s = s + f3(1, 1, 1)
                s = s + f4(1, 1, 1, 1)
                i = i + 1
            }
            s
        """.trimIndent()
        val expected = (1 + 1 + 2 + 3 + 4).toLong() * iterations

        // OFF
        PerfFlags.SCOPE_POOL = false
        val t0 = System.nanoTime()
        val off = withContext(Dispatchers.Default) { parallelEval(workers, script) }
        val t1 = System.nanoTime()
        // ON
        PerfFlags.SCOPE_POOL = true
        val t2 = System.nanoTime()
        val on = withContext(Dispatchers.Default) { parallelEval(workers, script) }
        val t3 = System.nanoTime()
        // reset
        PerfFlags.SCOPE_POOL = false

        off.forEach { assertEquals(expected, it) }
        on.forEach { assertEquals(expected, it) }

        val offMs = (t1 - t0) / 1_000_000.0
        val onMs = (t3 - t2) / 1_000_000.0
        val speedup = offMs / onMs
        println("[DEBUG_LOG] [BENCH] ConcurrencyCallBenchmark workers=$workers iters=$iterations each: OFF=${"%.3f".format(offMs)} ms, ON=${"%.3f".format(onMs)} ms, speedup=${"%.2f".format(speedup)}x")
    }
}
