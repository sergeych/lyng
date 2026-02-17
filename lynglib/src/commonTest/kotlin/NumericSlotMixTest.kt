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

    @Test
    fun testOptionalCallsWithCapturedLocals() = runTest {
        eval(
            """
            class Box(val b) {
                fun bump(x) = x + b
            }

            fun makeScale(k, offset) {
                return { x -> x * k + offset }
            }

            fun run() {
                var sum = 0.0
                for (i in 1..12) {
                    val scale = makeScale(1.5, i)
                    val base = i + 0.25
                    val left = scale(base)
                    var inner = 0.0
                    for (j in 1..3) {
                        val maybeBox: Box? = if ((i + j) % 3 == 0) Box(0.5) else null
                        val right = maybeBox?.bump(j) ?: (j + 1.0)
                        when (j) {
                            1 -> inner += right / 2.0
                            2 -> inner += right
                            else -> inner += right + 0.25
                        }
                    }
                    when (i % 3) {
                        0 -> sum += left + inner
                        1 -> sum += left - inner / 2.0
                        else -> sum += (left + inner) / 2.0
                    }
                }
                sum
            }

            val result = run()
            assert(result is Real)
            assert(abs(result - 197.75) < 1.0e-9)
            """.trimIndent()
        )
    }

    @Test
    fun testDelegatedVarsAndBoxingMix() = runTest {
        eval(
            """
            class NumDelegate(val map) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = map[name]
                override fun setValue(thisRef: Object, name: String, value: Object) { map[name] = value }
            }

            fun run() {
                val store = { "r": 0.5, "i": 1 }
                var r by NumDelegate(store)
                var i by NumDelegate(store)
                var acc = 0.0
                for (n in 1..8) {
                    val local = n
                    val bump = { x -> x + local }
                    r = (r as Real) + n * 0.25
                    i = (i as Int) + n
                    var inner = 0.0
                    for (j in 1..2) {
                        val mixed = bump(n) + (i as Int) + j
                        when (j) {
                            1 -> inner += mixed / 2.0
                            else -> inner += mixed + (r as Real)
                        }
                    }
                    if (n % 2 == 0)
                        acc += inner + (r as Real)
                    else
                        acc += inner / 2.0
                }
                acc + (r as Real) + (i as Int)
            }

            val result = run()
            assert(result is Real)
            assert(abs(result - 343.25) < 1.0e-9)
            """.trimIndent()
        )
    }

    @Test
    fun testIteratorLoopsWithOptionalIndex() = runTest {
        eval(
            """
            fun run() {
                val list = [1, 2.0, 3, 4.0]
                val inner = [1, 2, 3]
                var acc = 0.0
                for (v in list) {
                    val maybe = if (v is Real) list else null
                    val pick = maybe?[1] ?: 0
                    for (w in inner) {
                        val t = v * w
                        when (w) {
                            1 -> acc += t + pick
                            2 -> acc += t + 0.5
                            else -> acc += t - 1
                        }
                    }
                }
                acc
            }

            val result = run()
            assert(result is Real)
            assert(abs(result - 62.0) < 1.0e-9)
            """.trimIndent()
        )
    }
}
