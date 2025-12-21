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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class OOTest {
    @Test
    fun testClassProps() = runTest {
        eval("""
            import lyng.time
            
            class Point(x,y) {
                static val origin = Point(0,0)
                static var center = origin
            }
            assertEquals(Point(0,0), Point.origin)
            assertEquals(Point(0,0), Point.center)
            Point.center = Point(1,2)
            assertEquals(Point(0,0), Point.origin)
            assertEquals(Point(1,2), Point.center)
            
        """.trimIndent())
    }
    @Test
    fun testClassMethods() = runTest {
        eval("""
            import lyng.time
            
            class Point(x,y) {
                private static var data = null
                
                static fun getData() { data }
                static fun setData(value) { 
                    data = value 
                    callFrom()
                }
                static fun callFrom() {
                    data = data + "!"
                }
            }
            assertEquals(Point(0,0), Point(0,0) )
            assertEquals(null, Point.getData() )
            Point.setData("foo")
            assertEquals( "foo!", Point.getData() )
        """.trimIndent())
    }

    @Test
    fun testDynamicGet() = runTest {
        eval("""
            val accessor = dynamic {
                get { name ->
                    if( name == "foo" ) "bar" else null
                }
            }
           
            println("-- " + accessor.foo)
            assertEquals("bar", accessor.foo)
            assertEquals(null, accessor.bar)
            
        """.trimIndent())
    }

    @Test
    fun testDelegateSet() = runTest {
        eval("""
            var setValueForBar = null
            val accessor = dynamic {
                get { name ->
                    when(name) {
                        "foo" -> "bar"
                        "bar" -> setValueForBar 
                        else -> null
                    }
                }
                set { name, value ->
                    if( name == "bar" )
                        setValueForBar = value
                    else throw IllegalAssignmentException("Can't assign "+name)
                }
            }
           
            assertEquals("bar", accessor.foo)
            assertEquals(null, accessor.bar)
            accessor.bar = "buzz"
            assertEquals("buzz", accessor.bar)
            
            assertThrows {
                accessor.bad = "!23"
            }
        """.trimIndent())
    }

    @Test
    fun testDynamicIndexAccess() = runTest {
        eval("""
            val store = Map()
            val accessor = dynamic {
                get { name ->
                    store[name]
                }
                set { name, value ->
                    store[name] = value
                }
            }
           assertEquals(null, accessor["foo"])
           assertEquals(null, accessor.foo)
           accessor["foo"] = "bar"
           assertEquals("bar", accessor["foo"])
           assertEquals("bar", accessor.foo)
        """.trimIndent())
    }

    @Test
    fun testMultilineConstructor() = runTest {
        eval("""
            class Point(
                x,
                y
            ) 
            assertEquals(Point(1,2), Point(1,2) )
            """.trimIndent())
    }

    @Test
    fun testDynamicClass() = runTest {
        eval("""
            
            fun getContract(contractName) {
                dynamic {
                    get { name ->
                        println("Call: %s.%s"(contractName,name))
                    }
                }
            }
            getContract("foo").bar
       """)
    }

    @Test
    fun testDynamicClassReturn2() = runTest {
        // todo: should work without extra parenthesis
        // see below
        eval("""
            
            fun getContract(contractName) {
                println("1")
                dynamic {
                    get { name ->
                        println("innrer %s.%s"(contractName,name))
                        { args... ->
                            if( name == "bar" ) args.sum() else null
                        }
                    }
                }
            }
            
            val cc = dynamic {
                get { name ->
                    println("Call cc %s"(name))
                    getContract(name)
                }
            }
            
            val x = cc.foo.bar
            println(x)
            x(1,2,3)
            assertEquals(6, x(1,2,3))
            //               v  HERE    v
            assertEquals(15, cc.foo.bar(10,2,3))
       """)
    }

    @Test
    fun testClassInitialization() = runTest {
        eval("""
            var countInstances = 0
            class Point(val x: Int, val y: Int) {
               println("Class initializer is called 1")
               var magnitude
               
               /*
                    init {} section optionally provide initialization code that is called on each instance creation.
                    it should have the same access to this.* and constructor parameters as any other member
                    function.
               */
               init {
                  countInstances++
                  magnitude = Math.sqrt(x*x + y*y)
               }
            }
            
            val p = Point(1, 2)
            assertEquals(1, countInstances)
            assertEquals(p, Point(1,2) )
            assertEquals(2, countInstances)
            """.trimIndent())
    }

    @Test
    fun testMIInitialization() = runTest {
        eval("""
            var order = []
            class A {
                init { order.add("A") }
            }
            class B : A {
                init { order.add("B") }
            }
            class C {
                init { order.add("C") }
            }
            class D : B, C {
                init { order.add("D") }
            }
            D()
            assertEquals(["A", "B", "C", "D"], order)
        """)
    }

    @Test
    fun testMIDiamondInitialization() = runTest {
        eval("""
            var order = []
            class A {
                init { order.add("A") }
            }
            class B : A {
                init { order.add("B") }
            }
            class C : A {
                init { order.add("C") }
            }
            class D : B, C {
                init { order.add("D") }
            }
            D()
            assertEquals(["A", "B", "C", "D"], order)
        """)
    }

    @Test
    fun testInitBlockInDeserialization() = runTest {
        eval("""
            import lyng.serialization
            var count = 0
            class A {
                init { count++ }
            }
            val a1 = A()
            val coded = Lynon.encode(a1)
            val a2 = Lynon.decode(coded)
            assertEquals(2, count)
        """)
    }
}