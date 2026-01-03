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
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OOTest {
    @Test
    fun testClassProps() = runTest {
        eval(
            """
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
            
        """.trimIndent()
        )
    }

    @Test
    fun testClassMethods() = runTest {
        eval(
            """
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
        """.trimIndent()
        )
    }

    @Test
    fun testDynamicGet() = runTest {
        eval(
            """
            val accessor = dynamic {
                get { name ->
                    if( name == "foo" ) "bar" else null
                }
            }
           
            println("-- " + accessor.foo)
            assertEquals("bar", accessor.foo)
            assertEquals(null, accessor.bar)
            
        """.trimIndent()
        )
    }

    @Test
    fun testDelegateSet() = runTest {
        eval(
            """
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
        """.trimIndent()
        )
    }

    @Test
    fun testDynamicIndexAccess() = runTest {
        eval(
            """
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
        """.trimIndent()
        )
    }

    @Test
    fun testMultilineConstructor() = runTest {
        eval(
            """
            class Point(
                x,
                y
            ) 
            assertEquals(Point(1,2), Point(1,2) )
            """.trimIndent()
        )
    }

    @Test
    fun testDynamicClass() = runTest {
        eval(
            """
            
            fun getContract(contractName) {
                dynamic {
                    get { name ->
                        println("Call: %s.%s"(contractName,name))
                    }
                }
            }
            getContract("foo").bar
       """
        )
    }

    @Test
    fun testDynamicClassReturn2() = runTest {
        // todo: should work without extra parenthesis
        // see below
        eval(
            """
            
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
       """
        )
    }

    @Test
    fun testClassInitialization() = runTest {
        eval(
            """
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
            """.trimIndent()
        )
    }

    @Test
    fun testMIInitialization() = runTest {
        eval(
            """
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
        """
        )
    }

    @Test
    fun testMIDiamondInitialization() = runTest {
        eval(
            """
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
        """
        )
    }

    @Test
    fun testInitBlockInDeserialization() = runTest {
        eval(
            """
            import lyng.serialization
            var count = 0
            class A {
                init { count++ }
            }
            val a1 = A()
            val coded = Lynon.encode(a1)
            val a2 = Lynon.decode(coded)
            assertEquals(2, count)
        """
        )
    }

    @Test
    fun testDefaultCompare() = runTest {
        eval(
            """
            class Point(val x: Int, val y: Int) 
            
            assertEquals(Point(1,2), Point(1,2) )
            assert( Point(1,2) != Point(2,1) )
            assert( Point(1,2) == Point(1,2) )
            
        """.trimIndent()
        )
    }

    @Test
    fun testConstructorCallsWithNamedParams() = runTest {
        val scope = Script.newScope()
        val list = scope.eval(
            """
            import lyng.time
            
            class BarRequest(
                    id,
                    vaultId, userAddress, isDepositRequest, grossWeight, fineness, notes="",
                    createdAt = Instant.now().truncateToSecond(),
                    updatedAt = Instant.now().truncateToSecond()
            ) {
                // unrelated for comparison
                static val stateNames = [1, 2, 3]   

                val cell = cached { Cell[id] }
            }
            assertEquals( 5,5.toInt())
            val b1 = BarRequest(1, "v1", "u1", true, 1000, 999)
            val b2 = BarRequest(1, "v1", "u1", true, 1000, 999, createdAt: b1.createdAt, updatedAt: b1.updatedAt)
            assertEquals(b1, b2)
            assertEquals( 0, b1 <=> b2)
            [b1, b2]  
        """.trimIndent()
        ) as ObjList
        val b1 = list.list[0] as ObjInstance
        val b2 = list.list[1] as ObjInstance
        assertEquals(0, b1.compareTo(scope, b2))
    }

    @Test
    fun testPropAsExtension() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            class A(x) {
                private val privateVal = 100
                val p1 get() = x + 1
             }
             assertEquals(2, A(1).p1)
             
             fun A.f() = x + 5
             assertEquals(7, A(2).f())
             
             // The same, we should be able to add member values to a class;
             // notice it should access to the class public instance members, 
             // somewhat like it is declared in the class body
             val A.simple = x + 3
                          
             assertEquals(5, A(2).simple)
             
             // it should also work with properties:
             val A.p10 get() = x * 10
             assertEquals(20, A(2).p10)
        """.trimIndent())

        // important is that such extensions should not be able to access private members
        // and thus remove privateness:
        assertFails {
            scope.eval("val A.exportPrivateVal = privateVal; A(1).exportPrivateVal")
        }
        assertFails {
            scope.eval("val A.exportPrivateValProp get() = privateVal; A(1).exportPrivateValProp")
        }
    }

    @Test
    fun testExtensionsAreScopeIsolated() = runTest {
        val scope1 = Script.newScope()
        scope1.eval("""
            val String.totalDigits get() {
                // notice using `this`:
                this.characters.filter{ it.isDigit() }.size()
            }
            assertEquals(2, "answer is 42".totalDigits)
        """)
        val scope2 = Script.newScope()
        scope2.eval("""
            // in scope2 we didn't override `totalDigits` extension:
            assertThrows { "answer is 42".totalDigits }
        """.trimIndent())
    }

    @Test
    fun testCacheInClass() = runTest {
        eval("""
            class T(salt) {
                private var c
                 
                init {
                    println("create cached with "+salt)
                    c = cached { salt + "." }
                }
                
                fun getResult() = c()
            }
            
            val t1 = T("foo")
            val t2 = T("bar")
            assertEquals("bar.", t2.getResult())
            assertEquals("foo.", t1.getResult())
            
        """.trimIndent())
    }
}