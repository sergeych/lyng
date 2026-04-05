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
import net.sergeych.lyng.obj.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsiteSamplesTest {

    @Test
    fun testEverythingIsAnExpression() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Everything is an expression
            val x: Int = 10
            val status: String = if (x > 0) "Positive" else "Zero or Negative"

            // Even loops return values!
            val result = for (i in 1..5) {
                if (i == 3) break "Found 3!"
            } else "Not found"

            result
        """.trimIndent())
        assertEquals("Found 3!", (result as ObjString).value)
    }

    @Test
    fun testFunctionalPower() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Functional power with generics and collections
            val squares: List<Int> = (1..10)
                .filter { it % 2 == 0 }
                .map { it * it }

            squares
        """.trimIndent())
        assertTrue(result is ObjList)
        val list = result.list.map { (it as ObjInt).value }
        assertEquals(listOf(4L, 16L, 36L, 64L, 100L), list)
    }

    @Test
    fun testGenericsAndTypeAliases() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Generics and type aliases
            type Num = Int | Real
            
            class Box<out T: Num>(val value: T) {
                fun get(): T = value
            }
            
            val intBox = Box(42)
            val realBox = Box(3.14)
            [intBox.get(), realBox.get()]
        """.trimIndent())
        assertTrue(result is ObjList)
        val l = result.list
        assertEquals(42L, (l[0] as ObjInt).value)
        assertEquals(3.14, (l[1] as ObjReal).value)
    }

    @Test
    fun testStrictCompileTimeTypes() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            // Strict compile-time types and symbol resolution
            fun greet(name: String, count: Int) {
                for (i in 1..count) {
                    println("Hello, " + name + "!")
                }
            }
            
            greet("Lyng", 3)
        """.trimIndent())
    }

    @Test
    fun testFlexibleMapLiterals() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Flexible map literals and shorthands
            val id = 101
            val name = "Lyng"
            val base = { id:, name: } // Shorthand for id: id, name: name

            val full = { ...base, version: "1.5.4", status: "stable" }
            full
        """.trimIndent())
        assertTrue(result is ObjMap)
        val m = result.map
        assertEquals(101L, (m[ObjString("id")] as ObjInt).value)
        assertEquals("Lyng", (m[ObjString("name")] as ObjString).value)
        assertEquals("1.5.4", (m[ObjString("version")] as ObjString).value)
        assertEquals("stable", (m[ObjString("status")] as ObjString).value)
    }

    @Test
    fun testModernNullSafety() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Modern null safety
            var config: Map<String, Int>? = null
            config ?= { timeout: 30 } // Assign only if null

            val timeout = config?["timeout"] ?: 60
            timeout
        """.trimIndent())
        assertEquals(30L, (result as ObjInt).value)
    }

    @Test
    fun testDestructuringWithSplat() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Destructuring with splat operator
            val [first, middle..., last] = [1, 2, 3, 4, 5, 6]
            [first, middle, last]
        """.trimIndent())
        assertTrue(result is ObjList)
        val rl = result.list
        assertEquals(1L, (rl[0] as ObjInt).value)
        val middle = rl[1] as ObjList
        assertEquals(listOf(2L, 3L, 4L, 5L), middle.list.map { (it as ObjInt).value })
        assertEquals(6L, (rl[2] as ObjInt).value)
    }

    @Test
    fun testDiamondSafeMultipleInheritance() = runTest {
        val scope = Script.newScope()
        scope.eval("""
            // Diamond-safe Multiple Inheritance (C3 MRO)
            interface Logger {
                fun log(m: String) = println("[LOG] " + m)
            }
            interface Auth {
                fun login(u: String) = println("Login: " + u)
            }

            class App() : Logger, Auth {
                override fun run() {
                    log("Starting...")
                    login("admin")
                }
            }
            App().run()
        """.trimIndent())
    }

    @Test
    fun testExtensionFunctionsAndProperties() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Extension functions and properties
            fun String.shout(): String = this.uppercase() + "!!!"

            val List<T>.second: T get() = this[1]
            ["hello".shout(), [10, 20, 30].second]
        """.trimIndent())
        assertTrue(result is ObjList)
        val el = result.list
        assertEquals("HELLO!!!", (el[0] as ObjString).value)
        assertEquals(20L, (el[1] as ObjInt).value)
    }

    @Test
    fun testNonLocalReturns() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Non-local returns from closures
            fun findFirst<T>(list: Iterable<T>, predicate: (T)->Bool): T? {
                list.forEach {
                    if (predicate(it)) return@findFirst it
                }
                null
            }

            val found: Int? = findFirst([1, 5, 8, 12]) { it > 10 }
            found
        """.trimIndent())
        assertEquals(12L, (result as ObjInt).value)
    }

    @Test
    fun testNullableTypePredicate() = runTest {
        val scope = Script.newScope()
        val result = scope.eval(
            """
            // Type nullability checks
            fun describe<T>(x: T): String = when(T) {
                nullable -> "nullable"
                else -> "non-null"
            }
            type MaybeInt = Int?
            [describe<Int?>(null), describe<Int>(1), Int is nullable, MaybeInt is nullable]
            """.trimIndent()
        )
        assertTrue(result is ObjList)
        val list = result.list
        assertEquals("nullable", (list[0] as ObjString).value)
        assertEquals("non-null", (list[1] as ObjString).value)
        assertEquals(false, (list[2] as ObjBool).value)
        assertEquals(true, (list[3] as ObjBool).value)
    }

    @Test
    fun testEasyOperatorOverloading() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Easy operator overloading
            class Vector(val x: Real, val y: Real) {
                fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
                override fun toString(): String = "Vector(%g, %g)"(x, y)
            }

            val v1 = Vector(1, 2)
            val v2 = Vector(3, 4)
            (v1 + v2).toString()
        """.trimIndent())
        assertEquals("Vector(4, 6)", (result as ObjString).value)
    }

    @Test
    fun testPropertyDelegationToMap() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Property delegation to Map
            class User() {
                var name: String by Map()
            }

            val u = User()
            u.name = "Sergey"
            u.name
        """.trimIndent())
        assertEquals("Sergey", (result as ObjString).value)
    }

    @Test
    fun testImplicitCoroutines() = runTest {
        val scope = Script.newScope()
        val result = scope.eval("""
            // Implicit coroutines: parallelism without ceremony
            // import lyng.time // we don't need it for delay if it's available globally

            val d1 = launch {
                delay(100.milliseconds)
                "Task A finished"
            }
            val d2 = launch {
                delay(50.milliseconds)
                "Task B finished"
            }

            [d1.await(), d2.await()]
        """.trimIndent())
        assertTrue(result is ObjList)
        val dl = result.list
        assertEquals("Task A finished", (dl[0] as ObjString).value)
        assertEquals("Task B finished", (dl[1] as ObjString).value)
    }
}
