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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun testAddToArray2() = runTest {
        eval(
            $$"""
                import lyng.time
                val n = 700_000
                fun tm<T>(block: ()->T): T {
                    val t = Instant()
                    block().also {
                        println("tm: ${Instant() - t}")
                    }
                }
                val x = tm { List.fill(n) { it * 10 + 1 } }
                val y = tm { List.fill(n, n + 10) { it * 10 + 1 } }
                tm { x.add(-1) }
                tm { y.add(-2) }
        """.trimIndent()
        )
    }

    @Test
    fun testErrorMessage() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                val a = 1
                a++
            """.trimIndent())
        }
        assertContains(ex.errorMessage, "can't reassign val a")
    }

    @Test
    fun testAssignOpErrorMessageFromExample() = runTest {
        val source = Source(
            "examples/error1.lyng",
            """
                val a = 1
                a += 2
            """.trimIndent()
        )

        val ex = assertFailsWith<ScriptError> {
            Script.newScope().eval(source)
        }

        assertContains(ex.errorMessage, "can't reassign val a")
    }

    @Test
    fun testClosuresInLaunchPool() = runTest {
        eval($$"""
            val result = Set()
            val mu = Mutex()
            fn doSomething(value) {
                delay(100)
                println(value)
                mu.withLock {
                    result += value
                }
            }

            val lp = LaunchPool(4, 1000)
            for (i in 1 .. 10) {
                val ii: Int = i
                lp.launch {
                    doSomething( ii )
                }
            }
            println("all tasks were placed into lauchpool")
            lp.closeAndJoin()
            println("ALL DONE: $result")
            assertEquals((1..10).toSet(), result)
        """.trimIndent())
    }

    @Test
    fun testElvisBreak() = runTest {
        eval("""
            fun t(x: Int?): Int? =
                if( x == null || x == 3 ) null
                else 100
            fun needInt(x: Int): Int = x
                
            var cnt = -1    
            while( true ) {
                val x = t(cnt++) ?: break
                assertEquals(100, x)
                assertEquals(100, needInt(x))
                assertEquals(101, x + 1)
            }
            assert( t(3) == null )
            assert( cnt == 4 )
        """.trimIndent())
    }

    @Test
    fun testReceivers1() = runTest {
        eval("""
            class RA {
                fun a() { println("a") }
            }
            class RB {
                fun b() { println("b") }
            }
            
            fun ta( f: RA.()->Unit ) {
                val instance = RA()
                with(instance) { f(this) }
            }
            fun tb( f: RB.()->Unit ) {
                val b = RB()
                with(b) { f(this) }
            }
            ta {
                a()
                tb { 
                    b()
                    // but important: a() must still be accessible 
                    // because it is inner block, sort of closure:
                    a()
                }
            }
        """.trimIndent())
    }

    @Test
    fun testContextReceiverFunctionType() = runTest {
        eval("""
            class RA {
                fun value(): Int = 10
            }
            class RB {
                fun value(): Int = 20
            }

            fun ta(f: RA.()->Int): Int {
                val instance = RA()
                return with(instance) { f(this) }
            }

            fun tb(f: context(RA) RB.()->Int): Int {
                val instance = RB()
                return with(instance) { f(this) }
            }

            val result = ta {
                val block: context(RA) RB.()->Int = {
                    value() + this@RA.value()
                }
                tb(block)
            }

            assertEquals(30, result)
        """.trimIndent())
    }

    @Test
    fun testNestedReceiverQualifiedThis() = runTest {
        eval("""
            class RA {
                fun value(): Int = 1
            }
            class RB {
                fun value(): Int = 2
            }

            fun ta(f: RA.()->Int): Int {
                val instance = RA()
                return with(instance) { f(this) }
            }
            fun tb(f: RB.()->Int): Int {
                val instance = RB()
                return with(instance) { f(this) }
            }

            val result = ta {
                tb {
                    value() + this@RA.value()
                }
            }

            assertEquals(3, result)
        """.trimIndent())
    }

    @Test
    fun testReceiverAmbiguityRequiresQualifiedThis() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class RA {
                    fun shared(): Int = 10
                }
                class RC {
                    fun shared(): Int = 30
                }
                class RB

                fun ta(f: RA.()->Int): Int {
                    val instance = RA()
                    return with(instance) { f(this) }
                }
                fun tc(f: RC.()->Int): Int {
                    val instance = RC()
                    return with(instance) { f(this) }
                }
                fun tb(f: context(RA, RC) RB.()->Int): Int {
                    val instance = RB()
                    return with(instance) { f(this) }
                }

                ta {
                    tc {
                        val block: context(RA, RC) RB.()->Int = {
                            shared()
                        }
                        tb(block)
                    }
                }
            """.trimIndent())
        }
        assertContains(ex.message ?: "", "ambiguous between receivers RA, RC")
    }
}
