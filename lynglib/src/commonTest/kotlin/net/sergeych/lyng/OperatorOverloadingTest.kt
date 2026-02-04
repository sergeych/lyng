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

class OperatorOverloadingTest {
    @Test
    fun testBinaryOverloading() = runTest {
        eval("""
            class Vector(var x: Int, var y: Int) {
                fun plus(other: Vector) = Vector(this.x + other.x, this.y + other.y)
                fun minus(other: Vector) = Vector(this.x - other.x, this.y - other.y)
                fun equals(other: Vector) = this.x == other.x && this.y == other.y
                override fun toString() = "Vector(" + this.x + ", " + this.y + ")"
            }
            
            val v1 = Vector(1, 2)
            val v2 = Vector(3, 4)
            val v3 = v1 + v2
            assertEquals(Vector(4, 6), v3)
            assertEquals(Vector(-2, -2), v1 - v2)
        """.trimIndent())
    }

    @Test
    fun testUnaryOverloading() = runTest {
        eval("""
            class Vector(var x: Int, var y: Int) {
                fun negate() = Vector(-this.x, -this.y)
                fun equals(other: Vector) = this.x == other.x && this.y == other.y
            }
            val v1 = Vector(1, 2)
            assertEquals(Vector(-1, -2), -v1)
        """.trimIndent())
    }

    @Test
    fun testPlusAssignOverloading() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun plusAssign(x: Int) { this.n = this.n + x }
            }
            val c = Counter(10)
            c += 5
            assertEquals(15, c.n)
        """.trimIndent())
    }

    @Test
    fun testPlusAssignFallback() = runTest {
        eval("""
            class Vector(var x: Int, var y: Int) {
                fun plus(other: Vector) = Vector(this.x + other.x, this.y + other.y)
                fun equals(other: Vector) = this.x == other.x && this.y == other.y
            }
            var v = Vector(1, 2)
            v += Vector(3, 4)
            assertEquals(Vector(4, 6), v)
        """.trimIndent())
    }

    @Test
    fun testCompareOverloading() = runTest {
        eval("""
            class Box(var size: Int) {
                fun compareTo(other: Box) = this.size - other.size
            }
            val b1 = Box(10)
            val b2 = Box(20)
            assertEquals(true, b1 < b2)
            assertEquals(true, b2 > b1)
            assertEquals(false, b1 > b2)
        """.trimIndent())
    }

    @Test
    fun testIncDecOverloading() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun plus(x: Int) = Counter(this.n + x)
                fun equals(other: Counter) = this.n == other.n
            }
            var c = Counter(10)
            val oldC = c++
            assertEquals(Counter(11), c)
            assertEquals(Counter(10), oldC)
            val newC = ++c
            assertEquals(Counter(12), c)
            assertEquals(Counter(12), newC)
        """.trimIndent())
    }

    @Test
    fun testContainsOverloading() = runTest {
        eval("""
            class MyRange(var min: Int, var max: Int) {
                override fun contains(x: Int) = x >= this.min && x <= this.max
            }
            val r = MyRange(1, 10)
            assertEquals(true, 5 in r)
            assertEquals(false, 15 in r)
        """.trimIndent())
    }
}
