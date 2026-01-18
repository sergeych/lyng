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
import net.sergeych.lyng.Statement
import net.sergeych.lyng.eval
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.toSource
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
        scope.eval(
            """
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
        """.trimIndent()
        )

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
        scope1.eval(
            """
            val String.totalDigits get() {
                // notice using `this`:
                this.characters.filter{ it.isDigit() }.size()
            }
            assertEquals(2, "answer is 42".totalDigits)
        """
        )
        val scope2 = Script.newScope()
        scope2.eval(
            """
            // in scope2 we didn't override `totalDigits` extension:
            assertThrows { "answer is 42".totalDigits }
        """.trimIndent()
        )
    }

    @Test
    fun testCacheInClass() = runTest {
        eval(
            """
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
            
        """.trimIndent()
        )
    }

    @Test
    fun testLateInitValsInClasses() = runTest {
        assertFails {
            eval(
                """
                class T {
                    val x
                }
            """
            )
        }

        assertFails {
            eval("val String.late")
        }

        eval(
            """
            // but we can "late-init" them in init block:
            class OK {
                val x
                
                init {
                    x = "foo"
                }
            }
            val ok = OK()
            assertEquals("foo", ok.x)
            
            // they can't be reassigned:
            assertThrows(IllegalAssignmentException) {
                ok.x = "bar"
            }
            
            // To test access before init, we need a trick:
            class AccessBefore {
                val x
                fun readX() = x
                init {
                    assertEquals(x, Unset)
                    // if we call readX() here, x is Unset. 
                    // Just reading it is fine, but using it should throw:
                    assertThrows(UnsetException) { readX() + 1 }
                    x = 42
                }
            }
            AccessBefore()
        """.trimIndent()
        )
    }

    @Test
    fun testPrivateSet() = runTest {
        eval(
            """
            class A {
                var y = 100
                    private set
                fun setValue(newValue) { y = newValue }
            }
            assertEquals(100, A().y)
            assertThrows(IllegalAccessException) { A().y = 200 }
            val a = A()
            a.setValue(200)
            assertEquals(200, a.y)
            
            class B(initial) {
                var y = initial
                    protected set
            }
            class C(initial) : B(initial) {
                fun setBValue(v) { y = v }
            }
            val c = C(10)
            assertEquals(10, c.y)
            assertThrows(IllegalAccessException) { c.y = 20 }
            c.setBValue(30)
            assertEquals(30, c.y)
            
            class D {
                private var _y = 0
                var y 
                    get() = _y
                    private set(v) { _y = v }
                fun setY(v) { y = v }
            }
            val d = D()
            assertEquals(0, d.y)
            assertThrows(IllegalAccessException) { d.y = 10 }
            d.setY(20)
            assertEquals(20, d.y)
        """
        )
    }

    @Test
    fun testValPrivateSetError() = runTest {
        assertFails {
            eval("class E { val x = 1 private set }")
        }
    }

    @Test
    fun testAbstractClassesAndOverridingProposal() = runTest {
        val scope = Script.newScope()
        /*
            Abstract class is a sort of interface on steroidsL it is a class some members/methods of which
            are required to be implemented by heirs. Still it is a regular class in all other respects.
            Just can't be instantiated
         */
        scope.eval(
            """
            // abstract modifier is required. It can have a constructor, or be without it:    
            abstract class A(someParam=1) {
                // if the method is marked as abstract, it has no body:
                abstract fun foo(): Int
                
                // abstract var/var have no initializer:
                abstract var bar
            }
            // can't create instance of the abstract class:
            assertThrows { A() }
            """.trimIndent()
        )
        // create abstract method with body or val/var with initializer is an error:
        assertFails { scope.eval("abstract class B { abstract fun foo() = 1 }") }
        assertFails { scope.eval("abstract class C { abstract val bar = 1 }") }

        // inheriting an abstract class without implementing all of it abstract members and methods
        // is not allowed:
        assertFails { scope.eval("class D : A(1) { override fun foo() = 10 }") }

        // but it is allowed to inherit in another abstract class:
        scope.eval("abstract class E : A(1) { override fun foo() = 10 }")

        // implementing all abstracts let us have regular class:
        scope.eval(
            """
            class F : E() {  override val bar = 11 }
            assertEquals(10, F().foo())
            assertEquals(11, F().bar)
            """.trimIndent()
        )

        // Another possibility to override symbol is multiple inheritance: the parent that
        // follows the abstract class in MI chain can override the abstract symbol:
        scope.eval(
            """
            // This implementor know nothing of A but still implements de-facto its needs:
            class Implementor {
                val bar = 3
                fun foo() = 1
            }
            
            // now we can use MI to implement abstract class:
            class F2 : A(42), Implementor 
            
            assertEquals(1, F2().foo())
            assertEquals(3, F2().bar)
            """
        )
    }

    @Test
    fun testAbstractAndOverrideEdgeCases() = runTest {
        val scope = Script.newScope()

        // 1. abstract private is an error:
        assertFails { scope.eval("abstract class Err { abstract private fun foo() }") }
        assertFails { scope.eval("abstract class Err { abstract private val x }") }

        // 2. private member in parent is not visible for overriding:
        scope.eval(
            """
            class Base {
                private fun secret() = 1
                fun callSecret() = secret()
            }
            class Derived : Base() {
                // This is NOT an override, but a new method
                fun secret() = 2
            }
            val d = Derived()
            assertEquals(2, d.secret())
            assertEquals(1, d.callSecret())
            """.trimIndent()
        )
        // Using override keyword when there is only a private member in parent is an error:
        assertFails { scope.eval("class D2 : Base() { override fun secret() = 3 }") }

        // 3. interface can have state (constructor, fields, init):
        scope.eval(
            """
            interface I(val x) {
                var y = x * 2
                val z
                init {
                    z = y + 1
                }
                fun foo() = x + y + z
            }
            class Impl : I(10)
            val impl = Impl()
            assertEquals(10, impl.x)
            assertEquals(20, impl.y)
            assertEquals(21, impl.z)
            assertEquals(51, impl.foo())
            """.trimIndent()
        )

        // 4. closed members cannot be overridden:
        scope.eval(
            """
            class G {
                closed fun locked() = "locked"
                closed val permanent = 42
            }
            """.trimIndent()
        )
        assertFails { scope.eval("class H : G() { override fun locked() = \"free\" }") }
        assertFails { scope.eval("class H : G() { override val permanent = 0 }") }
        // Even without override keyword, it should fail if it's closed:
        assertFails { scope.eval("class H : G() { fun locked() = \"free\" }") }

        // 5. Visibility widening is allowed, narrowing is forbidden:
        scope.eval(
            """
            class BaseVis {
                protected fun prot() = 1
            }
            class Widened : BaseVis() {
                override fun prot() = 2 // Widened to public (default)
            }
            assertEquals(2, Widened().prot())
            
            class BasePub {
                fun pub() = 1
            }
            """.trimIndent()
        )
        // Narrowing:
        assertFails { scope.eval("class Narrowed : BasePub() { override protected fun pub() = 2 }") }
        assertFails { scope.eval("class Narrowed : BasePub() { override private fun pub() = 2 }") }
    }

    @Test
    fun testInterfaceImplementationByParts() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            // Interface with state (id) and abstract requirements
            interface Character(val id) {
                var health
                var mana
                fun isAlive() = health > 0
                fun status() = name + " (#" + id + "): " + health + " HP, " + mana + " MP"
                // name is also abstractly required by the status method, 
                // even if not explicitly marked 'abstract val' here, 
                // it will be looked up in MRO
            }

            // Part 1: Provides health
            class HealthPool(var health)

            // Part 2: Provides mana and name
            class ManaPool(var mana) {
                val name = "Hero"
            }

            // Composite class implementing Character by parts
            class Warrior(id, h, m) : HealthPool(h), ManaPool(m), Character(id)

            val w = Warrior(1, 100, 50)
            assertEquals(100, w.health)
            assertEquals(50, w.mana)
            assertEquals(1, w.id)
            assert(w.isAlive())
            assertEquals("Hero (#1): 100 HP, 50 MP", w.status())

            w.health = 0
            assert(!w.isAlive())
            """.trimIndent()
        )
    }
    @Test
    fun testBasicObjectExpression() = runTest {
        eval("""
            val x = object { val y = 1 }
            assertEquals(1, x.y)
            
            class Base(v) {
                val value = v
                fun squares() = value * value
            }
            
            val y = object : Base(2) {
                override val value = 5
            }
            
            assertEquals(25, y.squares())
            
            """.trimIndent())
    }

    @Test
    fun testArgsPriority() = runTest {
        eval("""
            class A(id) {
                var stored = null
                // Arguments should have priority on
                // instance fields
                fun setStored(id) { stored = id }
            }
            val a = A(1)
            assertEquals(1, a.id)
            assertEquals(null, a.stored)
            
            // Check that arguments of the call have the priority:
            a.setStored(2)
            assertEquals(1, a.id)
            assertEquals(2, a.stored)
        """.trimIndent())
    }

    /**
     * Demonstrates that function parameters are shadowed by class methods of the same name
     * when accessed within a block, but not in a single expression.
     */
    @Test
    fun testParameterShadowingConflict() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            class Tester() {
                fun id() { "method" }
                // This correctly returns "success"
                fun checkOk(id) = id
                // This incorrectly returns the 'id' method (a Callable) instead of "success"
                fun checkFail(id) {
                    id
                }
            }
            val t = Tester()
            if (t.checkOk("success") != "success") throw "checkOk failed"
            t.checkFail("success")
        """.trimIndent().toSource("repro"))

        assertEquals("success", result.toString(), "Parameter 'id' should shadow method 'id' in block")
    }

    @Test
    fun testOverrideVisibilityRules1() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            interface Base {
                abstract protected fun foo()
                
                fun bar() {
                    // it must see foo() as it is protected and 
                    // is declared here (even as abstract):
                    foo()
                }
            }
            class Derived : Base {
                protected val suffix = "!"
                
                private fun fooPrivateImpl() = "bar"
            
                override protected fun foo() { 
                    // it should access own private and all protected memberes here: 
                    fooPrivateImpl() + suffix  
                }
            }
            class Derived2: Base {
                private var value = 42
                
                private fun fooPrivateImpl() = value
                
                override protected fun foo() {
                    fooPrivateImpl()
                    value++
                }
            }
            assertEquals("bar!", Derived().bar())
            val d = Derived2()
            assertEquals(42, d.bar())
            assertEquals(43, d.bar())
        """.trimIndent())
        scope.createChildScope().eval("""            
            assertEquals("bar!", Derived().bar())
            assertEquals(42, Derived2().bar())
        """.trimIndent())
    }
    @Test
    fun testOverrideVisibilityRules2() = runTest {
        val scope = Script.newScope()
        val fn = scope.eval("""
            interface Base {
                abstract fun foo()
                
                fun bar() {
                    // it must see foo() as it is protected and 
                    // is declared here (even as abstract):
                    foo()
                }
            }
            class Derived : Base {
                protected val suffix = "!"
                
                private fun fooPrivateImpl() = "bar"
            
                override fun foo() { 
                    // it should access own private and all protected memberes here: 
                    fooPrivateImpl() + suffix  
                }
            }
            class Derived2: Base {
                private var value = 42
                
                private fun fooPrivateImpl() = value
                
                override fun foo() {
                    fooPrivateImpl()
                    value++
                }
            }
            assertEquals("bar!", Derived().bar())
            val d = Derived2()
            
            fun callBar() = d.bar()
            
            assertEquals(42, callBar())
            assertEquals(43, callBar())
            
            callBar
        """.trimIndent()) as Statement
        val s2 = Script.newScope()
        assertEquals(44L, fn.invoke(scope, fn).toKotlin(s2))
        assertEquals(45L, fn.invoke(s2, fn).toKotlin(s2))
    }

    @Test
    fun testToStringWithTransients() = runTest {
        eval("""
            class C(amount,@Transient transient=0) {
                val l by lazy { transient + amount }
                fun lock() {
                    if( transient < 10 ) 
                        C(amount).also { it.transient = transient + 10 }
                    else
                        this
                }   
            }
            println(C(1))
            println(C(1).lock().amount)
            println(C(1).lock().lock().amount)
        """.trimIndent())
    }
    @Test
    fun testToJsonString() = runTest {
        eval("""
            class C(amount,@Transient transient=0) {
                val l by lazy { transient + amount }
                fun lock() {
                    if( transient < 10 ) 
                        C(amount).also { it.transient = transient + 10 }
                    else
                        this
                }   
            }
            println(C(1))
            println(C(1).lock().amount)
            println(C(1).lock().lock().amount)
        """.trimIndent())
    }
}