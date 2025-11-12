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
 * JVM micro-benchmark for Scope frame pooling impact on call-heavy code paths.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CallPoolingBenchmarkTest {
    @Test
    fun benchmarkScopePoolingOnFunctionCalls() = runBlocking {
        val n = 300_000
        val script = """
            fun inc1(a) { a + 1 }
            fun inc2(a) { inc1(a) + 1 }
            fun inc3(a) { inc2(a) + 1 }
            var s = 0
            var i = 0
            while (i < $n) {
                s = inc3(s)
                i = i + 1
            }
            s
        """.trimIndent()

        // Baseline: pooling OFF
        PerfFlags.SCOPE_POOL = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] call-pooling x$n [SCOPE_POOL=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized: pooling ON
        PerfFlags.SCOPE_POOL = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] call-pooling x$n [SCOPE_POOL=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Each inc3 performs 3 increments per loop
        val expected = 3L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)

        // Reset flag to default (OFF) to avoid affecting other tests unintentionally
        PerfFlags.SCOPE_POOL = false
    }
}
