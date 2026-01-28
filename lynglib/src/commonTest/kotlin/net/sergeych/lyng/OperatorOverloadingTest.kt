package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test

class OperatorOverloadingTest {
    @Test
    fun testBinaryOverloading() = runTest {
        eval("""
            class Vector(x, y) {
                fun plus(other) = Vector(this.x + other.x, this.y + other.y)
                fun minus(other) = Vector(this.x - other.x, this.y - other.y)
                fun equals(other) = this.x == other.x && this.y == other.y
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
            class Vector(x, y) {
                fun negate() = Vector(-this.x, -this.y)
                fun equals(other) = this.x == other.x && this.y == other.y
            }
            val v1 = Vector(1, 2)
            assertEquals(Vector(-1, -2), -v1)
        """.trimIndent())
    }

    @Test
    fun testPlusAssignOverloading() = runTest {
        eval("""
            class Counter(n) {
                fun plusAssign(x) { this.n = this.n + x }
            }
            val c = Counter(10)
            c += 5
            assertEquals(15, c.n)
        """.trimIndent())
    }

    @Test
    fun testPlusAssignFallback() = runTest {
        eval("""
            class Vector(x, y) {
                fun plus(other) = Vector(this.x + other.x, this.y + other.y)
                fun equals(other) = this.x == other.x && this.y == other.y
            }
            var v = Vector(1, 2)
            v += Vector(3, 4)
            assertEquals(Vector(4, 6), v)
        """.trimIndent())
    }

    @Test
    fun testCompareOverloading() = runTest {
        eval("""
            class Box(size) {
                fun compareTo(other) = this.size - other.size
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
            class Counter(n) {
                fun plus(x) = Counter(this.n + x)
                fun equals(other) = this.n == other.n
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
            class MyRange(min, max) {
                override fun contains(x) = x >= this.min && x <= this.max
            }
            val r = MyRange(1, 10)
            assertEquals(true, 5 in r)
            assertEquals(false, 15 in r)
        """.trimIndent())
    }
}
