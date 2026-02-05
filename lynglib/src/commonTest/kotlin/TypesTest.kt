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

class TypesTest {

    @Test
    fun testTypeCollection1() = runTest {
        eval("""
           class Point(x: Real, y: Real)
           assert(Point(1,2).x == 1)
           assert(Point(1,2).y == 2)
           assert(Point(1,2) is Point)
        """.trimIndent())

    }
    @Test
    fun testTypeCollection2() = runTest {
        eval("""
           fun fn1(x: Real, y: Real): Real { x + y }
        """.trimIndent())

    }
    @Test
    fun testTypeCollection3() = runTest {
        eval("""
           class Test(a: Int) {
              fun fn1(x: Real, y: Real): Real { x + y }
           }
        """.trimIndent())
    }

    @Test
    fun testExternDeclarations() = runTest {
        eval("""
            extern fun foo1(a: String): Void
            assertThrows { foo1("1") }
           class Test(a: Int) {
              extern fun fn1(x: Real, y: Real): Real
//              extern val b: Int
           }
//           println("1")
           val t = Test(0)
//           println(t.b)
//           println("2")
           assertThrows {
            t.fn1(1,2)
           }
//           println("4")
           
        """.trimIndent())
    }

    @Test
    fun testUserClassCompareTo() = runTest {
        eval("""
            class Point(val a,b)
            
            assertEquals(Point(0,1), Point(0,1) )
            assertNotEquals(Point(0,1), Point(1,1) )
        """.trimIndent())
    }

    @Test
    fun testUserClassCompareTo2() = runTest {
        eval("""
            class Point(val a,b) {
                var c = 0
            }
            val p1 = Point(0,1)
            val p2 = Point(0,1)
            p1.c = 2
            p2.c = 2
            val p3 = Point(0,1)
            p3.c = 1
            assertEquals(Point(0,1), Point(0,1) )
            assertEquals(p1, p2)
            assertNotEquals(Point(0,1), Point(1,1) )
            assertNotEquals(Point(0,1), p3)
        """.trimIndent())
    }

    @Test
    fun testNumericInference() = runTest {
        eval("""
            val x = 1
            var y = 2.0
            assert( x is Int )
            assert( y is Real )
            assert( x + y is Real )
            assert( abs(x+y) is Real )
            assert( abs(x/y) is Real )
        """.trimIndent())
    }
    @Test
    fun testNumericInferenceBug1() = runTest {
        eval("""
            fun findSumLimit(f) {
                var sum = 0.0
                for( n in 1..100 ) {
                    val s0 = sum
                    sum += f(n)
                    assert( sum is Real )
                    assert( s0 is Real )
                    val delta = abs(sum - s0) / abs(sum)
                    assert( delta is Real )
                    println("abs(%g - %g) = %g"(sum, s0, abs(sum-s0)))
                    if( s0 != 0 )
                        assert( abs(sum-s0) < abs(sum) )
                    println("abs(%g) = %g"(sum, abs(sum)))
                    println( "delta calc: %g"(delta) )
//                    if( n > 3 ) assert( delta < 1.0 )
                    if( delta < 1.0e-4 ) {
                        println("limit reached after "+n+" rounds")
                        break sum
                    }
                    else
                        println("%g, delta=%g"(sum, delta))
                    n++
                }
                else {
                    println("limit not reached")
                    null
                }
            }
            
            val limit = findSumLimit { n -> 1.0/n/n }
            assert( limit != null )
            println("Result: "+limit)
        """.trimIndent())
    }

    @Test
    fun testNullableHints() = runTest {
        eval("""
            // nullable, without type os Object?
            class N(x=null)
            assertEquals(null, N().x)
            assertEquals("foo", N("foo").x)
            // nullable shortcut (x?)is same as (var x: Object?)
            class A(x?)
            assertEquals(null, A(null).x)
            assertEquals("ok", A("ok").x)
            
            // same in function: x? is a shortcut for (x: Object?)
            fun f(x?) = x?.let { x + "!" }
            assertEquals(null, f(null))
            assertEquals("ok!", f("ok"))
        """.trimIndent()
        )
    }
}
