/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Benchmarks
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

class RepresentativeBenchmarkTest {
    @Test
    fun benchmarkMixedOps() = runTest {
        if (!Benchmarks.enabled) return@runTest
        val iterations = 40000
        val script = """
            fun mixedBench(n) {
                var acc = 0
                var len = 0
                val list = [1,2,3,4,5,6,7,8]
                val map = {"a":1, "b":2, "c":3}
                fun bump(x) { x + 1 }
                var i = 0
                while(i < n) {
                    acc += i
                    acc += bump(i)
                    if( i % 2 == 0 ) acc += 3 else acc -= 1
                    acc += list[i % 8]
                    acc += map["a"]
                    if( i % 7 == 0 ) len += 1
                    if( i % 11 == 0 ) len += 1
                    i++
                }
                acc + len
            }
        """.trimIndent()

        val scope = Script.newScope()
        scope.eval(script)
        val expected = expectedValue(iterations)

        val start = TimeSource.Monotonic.markNow()
        val result = scope.eval("mixedBench($iterations)") as ObjInt
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        println("[DEBUG_LOG] [BENCH] mixed-ops elapsed=${elapsedMs} ms")
        assertEquals(expected, result.value)
    }

    private fun expectedValue(iterations: Int): Long {
        val list = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        var acc = 0L
        var len = 0L
        for (i in 0 until iterations) {
            acc += i
            acc += i + 1L
            if (i % 2 == 0) acc += 3 else acc -= 1
            acc += list[i % list.size]
            acc += 1
            if (i % 7 == 0) len += 1
            if (i % 11 == 0) len += 1
        }
        return acc + len
    }
}
