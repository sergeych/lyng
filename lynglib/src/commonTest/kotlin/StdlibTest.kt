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
import net.sergeych.lyng.Script
import net.sergeych.lyng.eval
import net.sergeych.lyng.evalNamed
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.getLyngExceptionMessage
import net.sergeych.lyng.obj.getLyngExceptionStackTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StdlibTest {
    @Test
    fun testIterableFilter() = runTest {
        eval("""
            assertEquals([2,4,6,8], (1..8).filter{ println("call2"); it % 2 == 0 }.toList() )
            println("-------------------")
            assertEquals([1,3,5,7], (1..8).filter{ println("call1"); it % 2 == 1 }.toList() )
        """.trimIndent())

    }

    @Test
    fun testFirstLast() = runTest {
        eval("""
            assertEquals(1, (1..8).first )
            assertEquals(8, (1..8).last )
        """.trimIndent())
    }

    @Test
    fun testTake() = runTest {
        eval("""
            val r = 1..8
            assertEquals([1,2,3], r.take(3).toList() )
            assertEquals([7,8], r.takeLast(2).toList() )
        """.trimIndent())
    }

    @Test
    fun testAnyAndAll() = runTest {
        eval("""
            assert( [1,2,3].any { it > 2 } )
            assert( ![1,2,3].any { it > 4 } )
            assert( [1,2,3].all { it <= 3 } )
            
        """.trimIndent())
    }

    @Test
    fun testRingBuffer() = runTest {
        eval("""
            val r = RingBuffer(3)
            assert( r is RingBuffer )
            assertEquals(0, r.size)
            assertEquals(3, r.capacity)
            
            r += 10
            assertEquals(1, r.size)
            assertEquals(10, r.first)
            
            r += 20
            assertEquals(2, r.size)
            assertEquals( [10, 20], r.toList() )
            
            r += 30
            assertEquals(3, r.size)
            assertEquals( [10, 20, 30], r.toList() )
            
            r += 40
            assertEquals(3, r.size)
            assertEquals( [20, 30, 40], r.toList() )
            
        """.trimIndent())
    }

    @Test
    fun testDrop() = runTest {
        eval("""
            assertEquals([7,8], (1..8).drop(6).toList() )
            assertEquals([1,2], (1..8).dropLast(6).toList() )
        """.trimIndent())
    }

    @Test
    fun testFlattenAndFilter() = runTest {
        eval("""
            assertEquals([1,2,3,4,5,6], [1,3,5].map { [it, it+1] }.flatten() )
            assertEquals([1,3,5], [null,1,null, 3,5].filterNotNull().toList())
        """)
    }

    @Test
    fun testFlatMap() = runTest {
        eval("""
            assertEquals([1,2,3,4,5,6], [1,3,5].flatMap { [it,it+1] }.toList() )
        """)
    }

    @Test
    fun testCount() = runTest {
        eval("""
            assertEquals(5, (1..10).toList().count { it % 2 == 1 } )
        """)
    }

    @Test
    fun testWith() = runTest {
        eval("""
            class Person(val name, var age)
            val p = Person("Alice", 30)
            
            val result = with(p) {
                assertEquals("Alice", name)
                assertEquals(30, age)
                age = 31
                "done"
            }
            
            assertEquals("done", result)
            assertEquals(31, p.age)
        """.trimIndent())
    }

    @Test
    fun testFilter1() = runTest {
        // range should be iterable if it is intrange
        eval("""
            val data = 1..5 // or [1, 2, 3, 4, 5]
            assert( data is Iterable<Int> )
            fun test() {
                data.filter { it % 2 == 0 }.map { it * it }
            }
            test()
        """.trimIndent())
    }

    @Test
    fun testFilter2() = runTest {
        eval("""
            val data = [1, 2, 3, 4, 5]
            assert( data is Iterable<Int> )
            fun test() {
                data.filter { it % 2 == 0 }.map { it * it }
            }
            test()
        """
        )
    }

    @Test
    fun testFilter3() = runTest {
        eval("""
            type Numeric = Int | Real

            fun process<T: Numeric>(items: List<T>): List<T> {
                items.filter { it > 0 }.map { it * it }
            }

            val data = [-2, -1, 0, 1, 2]
            println("Processed: " + process(data))

        """.trimIndent())
    }

    @Test
    fun testRandomSeededDeterministic() = runTest {
        eval("""
            val a = Random.seeded(123456)
            val b = Random.seeded(123456)

            for( i in 1..20 ) {
                assertEquals(a.nextInt(), b.nextInt())
                assertEquals(a.nextFloat(), b.nextFloat())
                assertEquals(a.next(1..100), b.next(1..100))
            }
        """.trimIndent())
    }

    @Test
    fun testRandomNextFloatBounds() = runTest {
        eval("""
            for( i in 1..400 ) {
                val x = Random.nextFloat()
                assert(x >= 0.0)
                assert(x < 1.0)
            }
        """.trimIndent())
    }

    @Test
    fun testRandomNextRangeVariants() = runTest {
        eval("""
            val rnd = Random.seeded(77)

            for( i in 1..300 ) {
                val x = rnd.next(10..<20)
                assert(x in 10..<20)
            }

            val allowed = [0, 3, 6, 9]
            for( i in 1..300 ) {
                val x = rnd.next(0..9 step 3)
                assert(x in allowed)
            }

            for( i in 1..300 ) {
                val ch = rnd.next('a'..<'f')
                assert(ch in 'a'..<'f')
            }

            for( i in 1..300 ) {
                val rf = rnd.next(1.5..<4.5)
                assert(rf >= 1.5)
                assert(rf < 4.5)
            }

            val qAllowed = [0.0, 0.25, 0.5, 0.75, 1.0]
            for( i in 1..300 ) {
                val q = rnd.next(0.0..1.0 step 0.25)
                assert(q in qAllowed)
            }
        """.trimIndent())
    }

    @Test
    fun testRandomRejectsOpenRange() = runTest {
        eval("""
            assertThrows(IllegalArgumentException) { Random.next(1..) }
            assertThrows(IllegalArgumentException) { Random.next(..10) }
            assertThrows(IllegalArgumentException) { Random.next(..) }
        """.trimIndent())
    }

    @Test
    fun testInference2() = runTest {
        eval(
            $$"""
                val a = 10
                val b = 3.0
                val c = floor(a / b)
                //assert(c is Real)
                c.toInt()
        """.trimIndent()
        )
    }

    @Test
    fun testStdlibGlobalFunctionInference() = runTest {
        eval(
            $$"""
                val absInt = abs(-5)
                assert(absInt is Int)
                absInt.toInt()

                val absReal = abs(-5.5)
                assert(absReal is Real)
                absReal.isNaN()

                val floorInt = floor(7)
                assert(floorInt is Int)
                floorInt.toInt()

                val floorReal = floor(7.9)
                assert(floorReal is Real)
                floorReal.toInt()

                val ceilInt = ceil(7)
                assert(ceilInt is Int)
                ceilInt.toInt()

                val ceilReal = ceil(7.1)
                assert(ceilReal is Real)
                ceilReal.toInt()

                val roundInt = round(7)
                assert(roundInt is Int)
                roundInt.toInt()

                val roundReal = round(7.4)
                assert(roundReal is Real)
                roundReal.toInt()

                val sinValue = sin(1)
                sinValue.isInfinite()
                assert(sinValue is Real)

                val cosValue = cos(1)
                cosValue.isNaN()
                assert(cosValue is Real)

                val tanValue = tan(1)
                tanValue.toInt()
                assert(tanValue is Real)

                val asinValue = asin(0.5)
                asinValue.toInt()
                assert(asinValue is Real)

                val acosValue = acos(0.5)
                acosValue.toInt()
                assert(acosValue is Real)

                val atanValue = atan(1)
                atanValue.toInt()
                assert(atanValue is Real)

                val sinhValue = sinh(1)
                sinhValue.isInfinite()
                assert(sinhValue is Real)

                val coshValue = cosh(1)
                coshValue.isNaN()
                assert(coshValue is Real)

                val tanhValue = tanh(1)
                tanhValue.toInt()
                assert(tanhValue is Real)

                val asinhValue = asinh(1)
                asinhValue.toInt()
                assert(asinhValue is Real)

                val acoshValue = acosh(2)
                acoshValue.toInt()
                assert(acoshValue is Real)

                val atanhValue = atanh(0.5)
                atanhValue.toInt()
                assert(atanhValue is Real)

                val expValue = exp(1)
                expValue.isInfinite()
                assert(expValue is Real)

                val lnValue = ln(2)
                lnValue.isNaN()
                assert(lnValue is Real)

                val log10Value = log10(100)
                log10Value.toInt()
                assert(log10Value is Real)

                val log2Value = log2(8)
                log2Value.toInt()
                assert(log2Value is Real)

                val powValue = pow(2, 8)
                powValue.isInfinite()
                assert(powValue is Real)

                val sqrtValue = sqrt(9)
                sqrtValue.isNaN()
                assert(sqrtValue is Real)

                val clampedInt = clamp(20, 0..10)
                assert(clampedInt is Int)
                clampedInt.toInt()

                val clampedReal = clamp(2.5, 0.0..10.0)
                assert(clampedReal is Real)
                clampedReal.toInt()
            """.trimIndent()
        )
    }

    @Test
    fun testErrorCatching() = runTest {
        val error = evalNamed("testErrorCatching", """
            val src = [1,2,3]
            val d = launch {
                try {
                    for( i in 0..3 ) src[i]
                } catch(e) {
                   e
                }
            }
            d.await()
        """.trimIndent()
        )

        val scope = when (error) {
            is ObjException -> error.scope
            is ObjInstance -> error.instanceScope
            else -> Script.newScope()
        }
        val trace = error.getLyngExceptionStackTrace(scope)
        val renderedTrace = trace.list.map { it.toString(scope).value }

        assertEquals("Index 3 out of bounds for length 3", error.getLyngExceptionMessage(scope))
        assertTrue(trace.list.size >= 2, "expected at least await and coroutine frames, got ${trace.list.size}")
        assertTrue(
            renderedTrace.all { it.contains("testErrorCatching:") },
            "unexpected trace entries: $renderedTrace"
        )
        assertTrue(
            renderedTrace.any { it.contains("launch") || it.contains("src[i]") },
            "trace should include the coroutine body: $renderedTrace"
        )
        assertTrue(
            renderedTrace.any { it.contains("d.await()") },
            "trace should include await site: $renderedTrace"
        )
    }

    @Test
    fun testCatchToIt() = runTest {
        eval("""
            var x = 0
            try {
                throw "msg1"
                x = 1
            }
            catch {
                assert(it.message == "msg1")
                x = 2
            }
            assertEquals(2, x)
        """.trimIndent())
    }
}
