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
import net.sergeych.lyng.eval
import kotlin.test.Test

class NumericSlotMixTest {

    @Test
    fun testMixedSlotsInClosuresAndLoops() = runTest {
        eval(
            """
            fun makeMulAdd(k) {
                return { x ->
                    val tmp = x * k
                    tmp + 0.5
                }
            }

            fun testMix() {
                var sum = 0.0
                var isum = 0
                val f = makeMulAdd(2.0)
                for (i in 1..20) {
                    val base = i
                    val g = { y -> y + base }
                    var inner = 0.0
                    for (j in 1..5) {
                        val v = f(j) + g(j)
                        if (j % 2 == 0)
                            inner += v
                        else
                            inner += v / 2.0
                        when (j) {
                            1 -> isum += j
                            2 -> isum += j * 2
                            3 -> isum += j + base
                            else -> isum += j
                        }
                    }
                    if (i % 3 == 0)
                        sum += inner + 0.25
                    else
                        sum += inner + 1.0
                }
                assert(sum is Real)
                assert(isum is Int)
                sum + isum
            }

            val result = testMix()
            assert(result is Real)
            assert(abs(result - 1965.5) < 1.0e-9)
            """.trimIndent()
        )
    }
}
