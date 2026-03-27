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
import kotlin.test.Test
import kotlin.test.assertTrue

class DelegationTest {

    @Test
    fun testSimpleDelegation() = runTest {
        eval("""
            class Proxy() : Delegate<Object,Object> {
                override fun getValue(r: Object, n: String): Object = 42
            }
            val x by Proxy()
            assertEquals(42, x)
        """)
    }

    @Test
    fun testConstructorVal() = runTest {
        eval("""
            class Foo(val v) {
                fun getV() = v
            }
            val f = Foo(42)
            assertEquals(42, f.v)
            assertEquals(42, f.getV())
        """)
    }

    @Test
    fun testBasicValVarDelegation() = runTest {
        eval("""
            class MapDelegate(val map) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = map[name]
                override fun setValue(thisRef: Object, name: String, value: Object) { map[name] = value }
            }
            
            val data = { "x": 10 }
            val x by MapDelegate(data)
            var y by MapDelegate(data)
            
            assertEquals(10, x)
            assertEquals(null, y)
            y = 20
            assertEquals(20, data["y"])
            assertEquals(20, y)
        """)
    }

    @Test
    fun testClassDelegationWithThisRef() = runTest {
        eval("""
            class Proxy(val target) : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = target[name]
                override fun setValue(thisRef: Object, name: String, value: Object) { target[name] = value }
            }
            
            class User(initialName) {
                val storage = { "name": initialName }
                var name by Proxy(storage)
            }
            
            val u = User("Alice")
            assertEquals("Alice", u.name)
            u.name = "Bob"
            assertEquals("Bob", u.name)
            assertEquals("Bob", u.storage["name"])
        """)
    }

    @Test
    fun testFunDelegation() = runTest {
        eval("""
            class ActionDelegate() : Delegate {
                override fun invoke(thisRef: Object, name: String, args...) {
                    val list: List = args as List
                    "Called %s with %d args: %s"(name, list.size, list.toString())
                }
            }
            
            fun greet by ActionDelegate()
            
            assertEquals("Called greet with 2 args: [hello,world]", greet("hello", "world"))
        """)
    }

    @Test
    fun testBindHook() = runTest {
        eval("""
            // Note: DelegateAccess might need to be defined or built-in
            // For the test, let's assume it's passed as an integer or we define it
            val VAL = "Val"
            val VAR = "Var"
            val CALLABLE = "Callable"
            
            class OnlyVal() : Delegate {
                override fun bind(name: String, access: String, thisRef: Object): Object {
                    if (access != VAL) throw "Only val allowed"
                    this
                }
                override fun getValue(thisRef: Object, name: String): Object = 42
            }
            
            val ok by OnlyVal()
            assertEquals(42, ok)
        """)
        val badThrown = try {
            eval("var bad by OnlyVal()")
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(badThrown)
    }

    @Test
    fun testStatelessObjectDelegate() = runTest {
        eval("""
            object Constant42 : Delegate {
                override fun getValue(thisRef: Object, name: String): Object = 42
            }
            
            class Foo {
                val a by Constant42
                val b by Constant42
            }
            
            val f = Foo()
            assertEquals(42, f.a)
            assertEquals(42, f.b)
        """)
    }

    @Test
    fun testLazyImplementation() = runTest {
        eval("""
            class Lazy(creatorParam: ()->Object) : Delegate<Object,Object> {
                private val creator: ()->Object = creatorParam
                private var value = Unset
                override fun getValue(thisRef: Object, name: String): Object {
                    if (this.value == Unset) {
                        this.value = creator()
                    }
                    this.value
                }
            }
            fun lazy(creator) = Lazy(creator)
            
            var counter = 0
            val x by lazy { counter++; "computed" }
            
            assertEquals(0, counter)
            assertEquals("computed", x)
            assertEquals(1, counter)
            assertEquals("computed", x)
            assertEquals(1, counter)
        """)
    }
    
    @Test
    fun testLocalDelegation() = runTest {
        eval("""
            class LocalProxy(val v) : Delegate<Object,Object> {
                override fun getValue(thisRef: Object, name: String): Object = v
            }
            
            fun test() {
                val x by LocalProxy(123)
                x
            }
            
            assertEquals(123, test())
        """)
    }

    @Test
    fun testStdlibLazy() = runTest {
        eval("""
            var counter = 0
            val x by lazy { counter++; "computed" }
            
            assertEquals(0, counter)
            assertEquals("computed", x)
            assertEquals(1, counter)
            assertEquals("computed", x)
        """)
        val badThrown = try {
            eval("var y by lazy { 1 }")
            false
        } catch (_: ScriptError) {
            true
        }
        assertTrue(badThrown)
    }

    @Test
    fun testPureLyngLazyPreservesReceiverAndClosure() = runTest {
        val scope = Script.newScope()
        scope.eval(
            """
            val GLOBAL_NUMBERS = [1,2,3]

            class PureLazy<T,ThisRefType=Object>(creatorParam: ThisRefType.()->T) : Delegate<T,ThisRefType> {
                private val creator: ThisRefType.()->T = creatorParam
                private var value = Unset

                override fun bind(name: String, access, thisRef: ThisRefType): Object = this

                override fun getValue(thisRef: ThisRefType, name: String): T {
                    if (value == Unset)
                        value = creator(thisRef)
                    value as T
                }
            }

            fun pureLazy<T,ThisRefType=Object>(creator: ThisRefType.()->T): Delegate<T,ThisRefType> = PureLazy(creator)

            class A {
                val numbers = [1,2,3]
                val fromThis: List by pureLazy { this.numbers }
                val fromScope: List by pureLazy { GLOBAL_NUMBERS }
            }

            class B {
                val a: A by pureLazy { A() }
                val test: List by pureLazy { (a as A).fromThis + [4] }
            }

            assertEquals([1,2,3], A().fromThis)
            assertEquals([1,2,3], A().fromScope)
            assertEquals([1,2,3,4], B().test)
            """.trimIndent()
        )
    }

    @Test
    fun testImportedPureLyngLazyPreservesReceiverAndClosure() = runTest {
        val scope = Script.newScope()
        scope.importManager.addTextPackages(
            """
            package repro.lazy

            import lyng.stdlib

            class PureLazy<T,ThisRefType=Object>(creatorParam: ThisRefType.()->T) : Delegate<T,ThisRefType> {
                private val creator: ThisRefType.()->T = creatorParam
                private var value = Unset

                override fun bind(name: String, access: DelegateAccess, thisRef: ThisRefType): Object {
                    if (access != DelegateAccess.Val) throw "lazy delegate can only be used with 'val'"
                    this
                }

                override fun getValue(thisRef: ThisRefType, name: String): T {
                    if (value == Unset)
                        value = with(thisRef, creator)
                    value as T
                }
            }
            """.trimIndent()
        )
        scope.eval(
            """
            import repro.lazy

            val GLOBAL_NUMBERS = [1,2,3]

            class A {
                val numbers = [1,2,3]
                val fromThis: List by PureLazy { this.numbers }
                val fromScope: List by PureLazy { GLOBAL_NUMBERS }
            }

            class B {
                val a: A by PureLazy { A() }
                val test: List by PureLazy { (a as A).fromThis + [4] }
            }

            assertEquals([1,2,3], A().fromThis)
            assertEquals([1,2,3], A().fromScope)
            assertEquals([1,2,3,4], B().test)
            """.trimIndent()
        )
    }

    @Test
    fun testLazyIsDelegate() = runTest {
        eval("""
            val l = lazy { 42 }
            assert(l is Object)
        """)
    }

    @Test
    fun testRealLifeBug1() = runTest {
        eval("""
            class Cell {
                val tags = [1,2,3]
            }
            class T {
                val cell: Cell by lazy { Cell() }
                val tags get() = (cell as Cell).tags
            }
            assertEquals([1,2,3], T().tags)
        """.trimIndent())
    }

    @Test
    fun testInstanceIsolation() = runTest {
        eval("""
            class CounterDelegate() : Delegate<Object,Object> {
                private var count = 0
                override fun getValue(thisRef: Object, name: String): Object = ++count
            }
            
            class Foo {
                val x by CounterDelegate()
            }
            
            val f1 = Foo()
            val f2 = Foo()
            
            assertEquals(1, f1.x)
            assertEquals(1, f2.x)
            assertEquals(2, f1.x)
            assertEquals(2, f2.x)
        """)
    }

    @Test
    fun testLazyRegexBug() = runTest {
        eval("""
            class T {
                val re by lazy { Regex(".*") }
            }
            val t = T()
            t.re
            // Second access triggered the bug before fix (value == Unset failed)
            t.re
        """)
    }

    @Test
    fun testEqualityRobustness() = runTest {
        eval("""
            val re1 = Regex("a")
            val re2 = Regex("a")
            // Equality should not throw even if types don't implement compareTo
            assertEquals(true, re1 == re1)
            assertEquals(false, re1 == re2)
            assertEquals(false, re1 == Unset)
            assertEquals(false, re1 == null)
        """)
    }

    @Test
    fun testLazy2() = runTest {
        eval("""
            class A {
                val tags = [1,2,3]
            }
            class B {
                val myA: A by lazy { A() }
                val tags: List by lazy { (myA as A).tags }
            }
            assert( B().tags == [1,2,3])
        """.trimIndent())
    }

    @Test
    fun testThisInLazy() = runTest {
        eval("""
            class A {
                val numbers = [1,2,3]
                val tags: List by lazy { this.numbers }
            }
            class B {
                val a: A by lazy { A() }
                val test: List by lazy { (a as A).tags + [4] }
            }
            assertEquals( [1,2,3], A().tags)
            assertEquals( [1,2,3,4], B().test)
        """)
    }

    @Test
    fun testScopeInLazy() = runTest {
        val s1 = Script.newScope()
        s1.eval("""val GLOBAL_NUMBERS = [1,2,3]""")
        s1.eval("""
            class A {
                val tags: List by lazy { GLOBAL_NUMBERS }
            }
            class B {
                val a: A by lazy { A() }
                val test: List by lazy { (a as A).tags + [4] }
            }
            assertEquals( [1,2,3], A().tags)
            assertEquals( [1,2,3,4], B().test)
        """)
    }

}
