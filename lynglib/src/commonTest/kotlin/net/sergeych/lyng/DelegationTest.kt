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

class DelegationTest {

    @Test
    fun testSimpleDelegation() = runTest {
        eval("""
            class Proxy() {
                fun getValue(r, n) = 42
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
            class MapDelegate(val map) {
                fun getValue(thisRef, name) = map[name]
                fun setValue(thisRef, name, value) { map[name] = value }
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
            class Proxy(val target) {
                fun getValue(thisRef, name) = target[name]
                fun setValue(thisRef, name, value) { target[name] = value }
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
            class ActionDelegate() {
                fun invoke(thisRef, name, args...) {
                    "Called %s with %d args: %s"(name, args.size, args.joinToString(","))
                }
            }
            
            fun greet by ActionDelegate()
            
            assertEquals("Called greet with 2 args: hello,world", greet("hello", "world"))
        """)
    }

    @Test
    fun testBindHook() = runTest {
        eval("""
            // Note: DelegateAccess might need to be defined or built-in
            // For the test, let's assume it's passed as an integer or we define it
            val VAL = 0
            val VAR = 1
            val CALLABLE = 2
            
            class OnlyVal() {
                fun bind(name, access, thisRef) {
                    if (access != VAL) throw "Only val allowed"
                    this
                }
                fun getValue(thisRef, name) = 42
            }
            
            val ok by OnlyVal()
            assertEquals(42, ok)
            
            assertThrows {
                eval("var bad by OnlyVal()")
            }
        """)
    }

    @Test
    fun testStatelessObjectDelegate() = runTest {
        eval("""
            object Constant42 {
                fun getValue(thisRef, name) = 42
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
            class Lazy(val creator) {
                private var value = Unset
                fun getValue(thisRef, name) {
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
            class LocalProxy(val v) {
                fun getValue(thisRef, name) = v
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
            
            assertThrows {
                eval("var y by lazy { 1 }")
            }
        """)
    }

    @Test
    fun testLazyIsDelegate() = runTest {
        eval("""
            val l = lazy { 42 }
            assert(l is Delegate)
        """)
    }

    @Test
    fun testRealLifeBug1() = runTest {
        eval("""
            class Cell {
                val tags = [1,2,3]
            }
            class T {
                val cell by lazy { Cell() }
                val tags get() = cell.tags
            }
            assertEquals([1,2,3], T().tags)
        """.trimIndent())
    }

    @Test
    fun testInstanceIsolation() = runTest {
        eval("""
            class CounterDelegate() {
                private var count = 0
                fun getValue(thisRef, name) = ++count
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
                val tags by lazy { myA.tags }
                val myA by lazy { A() }
            }
            assert( B().tags == [1,2,3])
        """.trimIndent())
    }

}
