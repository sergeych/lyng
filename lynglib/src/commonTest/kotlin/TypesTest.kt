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
import net.sergeych.lyng.ScriptError
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                    if( s0 != 0 )
                        assert( abs(sum-s0) < abs(sum) )
//                    if( n > 3 ) assert( delta < 1.0 )
                    if( delta < 1.0e-4 ) {
                        println("limit reached after "+n+" rounds")
                        break sum
                    }
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

    @Test
    fun testIsUnionIntersection() = runTest {
        eval("""
            class A
            class B
            class C: A, B
            val c = C()
            assert( c is A | B )
            assert( c is A & B )
            assert( !(c is A & String) )
            
            val v = 1
            assert( v is Int | String | Real )
            assert( !(v is String | Bool) )
        """.trimIndent())
    }

    @Test
    fun testAssertIsSmartCastEnablesMemberCall() = runTest {
        eval(
            """
            class Ctx {
                val contract = null
                fun println(msg: String) = msg
            }
            fun use(callContext) {
                assert(callContext is Ctx)
                assert(callContext.contract == null)
                callContext.println("hello")
            }
            assertEquals("hello", use(Ctx()))
            """.trimIndent()
        )
    }

    @Test
    fun testBareIsExpressionDoesNotSmartCastForMemberCall() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval(
                """
                class Ctx {
                    fun println(msg: String) = msg
                }
                fun use(callContext) {
                    callContext is Ctx
                    callContext.println("hello")
                }
                use(Ctx())
                """.trimIndent()
            )
        }
        assertTrue(ex.message?.contains("member access requires compile-time receiver type: println") == true)
    }

    @Test
    fun testListLiteralInferenceForBounds() = runTest {
        eval("""
            fun acceptInts<T: Int>(xs: List<T>) { }
            acceptInts([1, 2, 3])
            val base = [1, 2]
            acceptInts([...base, 3])
        """.trimIndent())
        eval("""
            fun acceptReals<T: Real>(xs: List<T>) { }
            acceptReals([1.0, 2.0, 3.0])
            val base = [1.0, 2.0]
            acceptReals([...base, 3.0])
        """.trimIndent())
        assertFailsWith<net.sergeych.lyng.ScriptError> {
            eval("""
                fun acceptInts<T: Int>(xs: List<T>) { }
                acceptInts([1, "a"])
            """.trimIndent())
        }
        assertFailsWith<net.sergeych.lyng.ScriptError> {
            eval("""
                fun acceptReals<T: Real>(xs: List<T>) { }
                acceptReals([1.0, "a"])
            """.trimIndent())
        }
    }

    @Test
    fun testMapLiteralInferenceForBounds() = runTest {
        eval("""
            fun acceptMap<T: Int>(m: Map<String, T>) { }
            acceptMap({ "a": 1, "b": 2 })
            val base = { "a": 1 }
            acceptMap({ ...base, "b": 3 })
        """.trimIndent())
        assertFailsWith<net.sergeych.lyng.ScriptError> {
            eval("""
                fun acceptMap<T: Int>(m: Map<String, T>) { }
                acceptMap({ "a": 1, "b": "x" })
            """.trimIndent())
        }
    }

    @Test
    fun testUnionTypeLists() = runTest {
        eval("""

            fun fMixed<T>(list: List<T>) {
                println(list)
                println(T)
                assert( T is Int | String | Bool )
                assert( !(T is Int) )
                assert( Int in T )
                assert( String in T )
            }
            fun fInts<T>(list: List<T>) {
                assert( T is Int )
                assert( Int in T )
                assert( !(String in T) )
            }
            fMixed([1, "two", true])
            fInts([1,2,3])
        """)
    }

    @Test
    fun testTypeAliases() = runTest {
        eval("""
            type Num = Int | Real
            type AB = A & B
            class A
            class B
            class C: A, B
            val c = C()
            assert( c is AB )
            assert( 1 is Num )
            assert( !(true is Num) )
            val v: Num = 1.5
            assert( v is Num )

            type Maybe<T> = T?
            fun f<T>(x: Maybe<T>) = x
            assertEquals(null, f(null))
            assertEquals(1, f(1))

            type IntList<T: Int> = List<T>
            fun accept<T: Int>(xs: IntList<T>) { }
            accept([1,2,3])
        """.trimIndent())
    }

    @Test
    fun testMultipleReceivers() = runTest {
        eval("""
            class R1(shared,r1="r1")
            class R2(shared,r2="r2")
            
            R1("s").apply {
                assertEquals("r1", r1)
                assertEquals("s", shared)
                R2("t").apply {
                    assertEquals("r2", r2)
                    assertEquals("t", shared)
                    assertEquals("s", this@R1.shared)
                    assertEquals("r1", this@R1.r1)
                    assertEquals("r2", this@R2.r2)
                    assertEquals("r1", r1)
                }
            }
            with(R1("s")) {
                assertEquals("r1", r1)
                assertEquals("s", shared)
                with(R2("t")) {
                    assertEquals("r2", r2)
                    assertEquals("t", shared)
                    assertEquals("s", this@R1.shared)
                    assertEquals("r1", this@R1.r1)
                    assertEquals("r1", r1)
                }
            }
        """)
    }

    @Test
    fun testLambdaTypes1() = runTest {
        val scope = Script.newScope()
        // declare: ok
        scope.eval("""
            var l1: (Int,String)->String
        """.trimIndent())
        // this should be Lyng compile time exception
        assertFailsWith<ScriptError> {
            scope.eval("""
            fun test() {
                // compiler should detect that l1 us called with arguments that does not match
                // declare type (Int,String)->String:
                l1()
            }
            """.trimIndent())
        }
    }

    @Test
    fun testLambdaTypesEllipsis() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            var l2: (Int,Object...,String)->Real
            var l4: (Int,String...,String)->Real
            var l3: (...)->Int
        """.trimIndent())
        assertFailsWith<ScriptError> {
            scope.eval("""
            fun testTooFew() {
                l2(1)
            }
            """.trimIndent())
        }
        assertFailsWith<ScriptError> {
            scope.eval("""
            fun testWrongHead() {
                l2("x", "y")
            }
            """.trimIndent())
        }
        assertFailsWith<ScriptError> {
            scope.eval("""
            fun testWrongEllipsis() {
                l4(1, 2, "x")
            }
            """.trimIndent())
        }
        scope.eval("""
            fun testOk1() { l2(1, "x") }
            fun testOk2() { l2(1, 2, 3, "x") }
            fun testOk3() { l3() }
            fun testOk4() { l3(1, true, "x") }
            fun testOk5() { l4(1, "a", "b", "x") }
        """.trimIndent())
    }

    @Test
    fun testSetTyped() = runTest {
        eval("""
            var s = Set<String>()
            val typed: Set<String> = s
            assertEquals(Set(), typed)
            
            s += "foo"
            assertEquals(Set("foo"), s)
            s -= "foo"
            assertEquals(Set(), s)
            s += ["foo", "bar"]
            assertEquals(Set("foo", "bar"), s)
        """.trimIndent())
    }

    @Test
    fun testListTyped() = runTest {
        eval("""
            var l = List<String>()
            val typed: List<String> = l
            assertEquals(List(), typed)

            l += "foo"
            assertEquals(List("foo"), l)
            l -= "foo"
            assertEquals(List(), l)

            l += ["foo", "bar"]
            assertEquals(List("foo", "bar"), l)
        """.trimIndent())
    }

    @Test
    fun testAliasesInGenerics1() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            type IntList<T: Int> = List<T>
            type IntMap<K,V> = Map<K,V>
            type IntSet<T: Int> = Set<T>
            type IntPair<T: Int> = Pair<T,T>
            type IntTriple<T: Int> = Triple<T,T,T>
            type IntQuad<T: Int> = Quad<T,T,T,T>

            import lyng.buffer
            type Tag = String | Buffer

            class X {
                var tags: Set<Tag> = Set()
            }
            val x = X()
            x.tags += "tag1"
            assertEquals(Set("tag1"), x.tags)
            x.tags += "tag2"
            assertEquals(Set("tag1", "tag2"), x.tags)
            x.tags += Buffer("tag3")
            assertEquals(Set("tag1", "tag2", Buffer("tag3")), x.tags)
            x.tags += Buffer("tag4")
            assertEquals(Set("tag1", "tag2", Buffer("tag3"), Buffer("tag4")), x.tags)
        """)
        scope.eval("""
            assert(x is X)
            x.tags += "42"
            assertEquals(Set("tag1", "tag2", Buffer("tag3"), Buffer("tag4"), "42"), x.tags)
            
        """.trimIndent())
        // now this must fail becaise element type does not match the declared:
        assertFailsWith<ScriptError> {
            scope.eval(
                """
            x.tags += 42
        """.trimIndent()
            )
        }
    }

    @Test
    fun testAliasesInGenericsList1() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            import lyng.buffer
            type Tag = String | Buffer

            class X {
                var tags: List<Tag> = List()
            }
            val x = X()
            x.tags += "tag1"
            assertEquals(List("tag1"), x.tags)
            x.tags += "tag2"
            assertEquals(List("tag1", "tag2"), x.tags)
            x.tags += Buffer("tag3")
            assertEquals(List("tag1", "tag2", Buffer("tag3")), x.tags)
            x.tags += ["tag4", Buffer("tag5")]
            assertEquals(List("tag1", "tag2", Buffer("tag3"), "tag4", Buffer("tag5")), x.tags)
        """)
        scope.eval("""
            assert(x is X)
            x.tags += "42"
            assertEquals(List("tag1", "tag2", Buffer("tag3"), "tag4", Buffer("tag5"), "42"), x.tags)
        """.trimIndent())
        assertFailsWith<ScriptError> {
            scope.eval(
                """
            x.tags += 42
        """.trimIndent()
            )
        }
    }
}
