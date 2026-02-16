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

class MixedCompareBenchmarkTest {
    @Test
    fun benchmarkMixedCompareOps() = runTest {
        if (!Benchmarks.enabled) return@runTest
        val iterations = 200000
        val script = """
            fun mixedCompareBench(n) {
                var acc = 0
                var r = 0.0
                val strs = ["a","b","aa","bb","abc","abd","zzz",""]
                var i = 0
                while(i < n) {
                    val si = strs[i % 8]
                    if( si == "a" ) acc += 1 else acc -= 1
                    if( si != "zzz" ) acc += 2
                    if( si == "" ) acc += 3

                    if( i < (i % 5) ) acc += 1 else acc -= 1
                    if( (i % 3) == 0 ) acc += 2

                    val r1 = i + 0.5
                    if( r1 > i ) acc += 1
                    if( i < r1 ) acc += 1
                    r += r1 * 0.25
                    if( r > 1000.0 ) acc += 1

                    i++
                }
                acc
            }
        """.trimIndent()

        val scope = Script.newScope()
        scope.eval(script)
        val expected = expectedValue(iterations)

        val start = TimeSource.Monotonic.markNow()
        val result = scope.eval("mixedCompareBench($iterations)") as ObjInt
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        println("[DEBUG_LOG] [BENCH] mixed-compare elapsed=${elapsedMs} ms")
        assertEquals(expected, result.value)
    }

    private fun expectedValue(iterations: Int): Long {
        val strs = arrayOf("a", "b", "aa", "bb", "abc", "abd", "zzz", "")
        var acc = 0L
        var r = 0.0
        var i = 0
        while (i < iterations) {
            val si = strs[i % 8]
            if (si == "a") acc += 1 else acc -= 1
            if (si != "zzz") acc += 2
            if (si == "") acc += 3

            if (i < (i % 5)) acc += 1 else acc -= 1
            if ((i % 3) == 0) acc += 2

            val r1 = i + 0.5
            if (r1 > i) acc += 1
            if (i < r1) acc += 1
            r += r1 * 0.25
            if (r > 1000.0) acc += 1

            i += 1
        }
        return acc
    }
}
