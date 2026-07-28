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
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

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
    fun testUnaryPlusDefaultIdentity() = runTest {
        eval("""
            assertEquals(42, +42)
            assertEquals(3.5, +3.5)
            assertEquals("abc", +"abc")

            class Box(val text: String) {
                fun upper() = text.upper()
            }

            assertEquals("ABC", (+Box("abc")).upper())
        """.trimIndent())
    }

    @Test
    fun testUnaryPlusOverloading() = runTest {
        eval("""
            class Counter(val n: Int) {
                fun unaryPlus() = Counter(this.n + 1)
                fun equals(other: Counter) = this.n == other.n
            }

            assertEquals(Counter(6), Counter(5).unaryPlus())
            assertEquals(Counter(6), +Counter(5))
        """.trimIndent())
    }

    @Test
    fun testUnaryPlusExtensionOverloading() = runTest {
        eval("""
            var out = ""
            fun String.unaryPlus() {
                out = out + this
            }

            "Hello".unaryPlus()
            " ".unaryPlus()
            "Lyng".unaryPlus()
            assertEquals("Hello Lyng", out)
            out = ""

            +"Hello"
            +" "
            +"Lyng"
            assertEquals("Hello Lyng", out)
        """.trimIndent())
    }

    @Test
    fun testUnaryPlusDslBuilderStyle() = runTest {
        eval("""
            class Tag(name: String) {
                val name = name
                var inner = ""

                fun child(tagName: String, block: Tag.()->void) {
                    val child = Tag(tagName)
                    with(child) { block(this) }
                    inner += child.render()
                }

                fun head(block: Tag.()->void) { child("head", block) }
                fun body(block: Tag.()->void) { child("body", block) }
                fun title(block: Tag.()->void) { child("title", block) }
                fun h1(block: Tag.()->void) { child("h1", block) }

                fun addText(text: String) {
                    inner += text
                }

                fun render() {
                    "<" + name + ">" + inner + "</" + name + ">"
                }
            }

            context(Tag)
            fun String.unaryPlus() {
                this@Tag.addText(this)
            }

            fun html(block: Tag.()->void) {
                val root = Tag("html")
                with(root) { block(this) }
                root.render()
            }

            val page = html {
                head {
                    title {
                        +"Demo"
                    }
                }
                body {
                    h1 {
                        +"Heading 1"
                    }
                }
            }

            assertEquals("<html><head><title>Demo</title></head><body><h1>Heading 1</h1></body></html>", page)
        """.trimIndent())
    }

    @Test
    fun testContextReceiverUnaryPlusDslBuilderStyle() = runTest {
        eval("""
            class Tag(name: String) {
                val name = name
                var inner = ""

                fun child(tagName: String, block: Tag.()->void) {
                    val child = Tag(tagName)
                    with(child) { block(this) }
                    inner += child.render()
                }

                fun h3(block: Tag.()->void) { child("h3", block) }

                fun addText(text: String) {
                    inner += text
                }

                fun render() {
                    "<" + name + ">" + inner + "</" + name + ">"
                }
            }

            context(Tag)
            fun String.unaryPlus() {
                this@Tag.addText(this)
            }

            fun html(block: Tag.()->void) {
                val root = Tag("html")
                with(root) { block(this) }
                root.render()
            }

            val page = html {
                h3 {
                    +"Heading 3"
                }
            }

            assertEquals("<html><h3>Heading 3</h3></html>", page)
            assertEquals("plain", +"plain")
        """.trimIndent())
    }

    @Test
    fun testContextReceiverExtensionIsHiddenOutsideContext() = runTest {
        val ex = assertFailsWith<Throwable> {
            eval("""
                class Tag {
                    fun wrap(text: String) = "[" + text + "]"
                }

                context(Tag)
                fun String.mark() = this@Tag.wrap(this)

                "x".mark()
            """.trimIndent())
        }
        assertContains(ex.message ?: "", "no such member: mark on String")
    }

    @Test
    fun testContextReceiverExtensionIsHiddenInWrongContext() = runTest {
        val ex = assertFailsWith<Throwable> {
            eval("""
                class Tag {
                    fun wrap(text: String) = "[" + text + "]"
                }
                class Other

                context(Tag)
                fun String.mark() = this@Tag.wrap(this)

                fun other(block: Other.()->void) {
                    with(Other()) { block(this) }
                }

                other {
                    "x".mark()
                }
            """.trimIndent())
        }
        assertContains(ex.message ?: "", "no such member: mark on String")
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
    fun testAssignOperatorMethodsOnValMember() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun plusAssign(x: Int) { n = n + x }
                fun minusAssign(x: Int) { n = n - x }
                fun mulAssign(x: Int) { n = n * x }
                fun divAssign(x: Int) { n = n / x }
                fun modAssign(x: Int) { n = n % x }
            }
            class Holder(val counter: Counter)

            val holder = Holder(Counter(10))
            holder.counter += 2
            assertEquals(12, holder.counter.n)
            holder.counter -= 3
            assertEquals(9, holder.counter.n)
            holder.counter *= 4
            assertEquals(36, holder.counter.n)
            holder.counter /= 6
            assertEquals(6, holder.counter.n)
            holder.counter %= 4
            assertEquals(2, holder.counter.n)
        """.trimIndent())
    }

    @Test
    fun testAssignOperatorFallbackOnMutableMember() = runTest {
        eval("""
            class Counter(val n: Int) {
                fun minus(x: Int) = Counter(n - x)
            }
            class Holder(var counter: Counter)

            val holder = Holder(Counter(10))
            holder.counter -= 3
            assertEquals(7, holder.counter.n)
        """.trimIndent())
    }

    @Test
    fun testBuiltinListPlusAssignOnVal() = runTest {
        eval("""
            val list = [1, 2]
            list += 3
            assertEquals([1, 2, 3], list)
        """.trimIndent())
    }

    @Test
    fun testBuiltinListMinusAssignOnVal() = runTest {
        eval("""
            val list = [1, 2, 3]
            list -= 2
            assertEquals([1, 3], list)
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
    fun testPlusAssignFallbackOnValReportsReadonlyError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class Vector(var x: Int, var y: Int) {
                    fun plus(other: Vector) = Vector(this.x + other.x, this.y + other.y)
                    fun equals(other: Vector) = this.x == other.x && this.y == other.y
                }
                val v = Vector(1, 2)
                v += Vector(3, 4)
            """.trimIndent())
        }

        assertContains(ex.errorMessage, "can't reassign val v")
    }

    @Test
    fun testMinusAssignOverloadingOnVal() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun minusAssign(x: Int) { this.n = this.n - x }
            }
            val c = Counter(10)
            c -= 3
            assertEquals(7, c.n)
        """.trimIndent())
    }

    @Test
    fun testMinusAssignFallbackOnValReportsReadonlyError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class Counter(var n: Int) {
                    fun minus(x: Int) = Counter(this.n - x)
                }
                val c = Counter(10)
                c -= 3
            """.trimIndent())
        }

        assertContains(ex.errorMessage, "can't reassign val c")
    }

    @Test
    fun testMulAssignOverloadingOnVal() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun mulAssign(x: Int) { this.n = this.n * x }
            }
            val c = Counter(10)
            c *= 3
            assertEquals(30, c.n)
        """.trimIndent())
    }

    @Test
    fun testMulAssignFallbackOnValReportsReadonlyError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class Counter(var n: Int) {
                    fun times(x: Int) = Counter(this.n * x)
                }
                val c = Counter(10)
                c *= 3
            """.trimIndent())
        }

        assertContains(ex.errorMessage, "can't reassign val c")
    }

    @Test
    fun testDivAssignOverloadingOnVal() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun divAssign(x: Int) { this.n = this.n / x }
            }
            val c = Counter(21)
            c /= 3
            assertEquals(7, c.n)
        """.trimIndent())
    }

    @Test
    fun testDivAssignFallbackOnValReportsReadonlyError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class Counter(var n: Int) {
                    fun div(x: Int) = Counter(this.n / x)
                }
                val c = Counter(21)
                c /= 3
            """.trimIndent())
        }

        assertContains(ex.errorMessage, "can't reassign val c")
    }

    @Test
    fun testModAssignOverloadingOnVal() = runTest {
        eval("""
            class Counter(var n: Int) {
                fun modAssign(x: Int) { this.n = this.n % x }
            }
            val c = Counter(23)
            c %= 5
            assertEquals(3, c.n)
        """.trimIndent())
    }

    @Test
    fun testModAssignFallbackOnValReportsReadonlyError() = runTest {
        val ex = assertFailsWith<ScriptError> {
            eval("""
                class Counter(var n: Int) {
                    fun mod(x: Int) = Counter(this.n % x)
                }
                val c = Counter(23)
                c %= 5
            """.trimIndent())
        }

        assertContains(ex.errorMessage, "can't reassign val c")
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
