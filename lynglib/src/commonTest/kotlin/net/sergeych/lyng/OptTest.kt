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

package net.sergeych.lyng

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

class OptTest {

    @Test
    fun testAddToArray() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            fun buildArray(n: Int) {
                val a: List<Int> = List.fill(n) { it * 10 + 1 }
                a.size
            }
            """.trimIndent()
        )

        repeat(3) { pass ->
            val size = scope.eval("buildArray(200000)").toInt()
            assertEquals(200000, size, "warmup pass ${pass + 1} failed")
            delay(100)
        }


        val passes = 4
        var bestMs = Long.MAX_VALUE
        var totalMs = 0L
        repeat(passes) { pass ->
            val start = TimeSource.Monotonic.markNow()
            val size = scope.eval("buildArray(10000000)").toInt()
            val elapsedMs = start.elapsedNow().inWholeMilliseconds
            assertEquals(10000000, size, "measured pass ${pass + 1} failed")
            bestMs = minOf(bestMs, elapsedMs)
            totalMs += elapsedMs
            println("add-to-array pass ${pass + 1}/$passes: ${elapsedMs}ms size=$size")
        }
        println("add-to-array best=${bestMs}ms avg=${totalMs / passes}ms after warmup")
    }
}
