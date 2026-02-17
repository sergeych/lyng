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
import net.sergeych.lyng.eval
import kotlin.test.Test

class IfNullAssignTest {

    @Test
    fun testBasicAssignment() = runTest {
        eval("""
            var x = null
            x ?= 42
            assertEquals(42, x)
            x ?= 100
            assertEquals(42, x)
        """.trimIndent())
    }

    @Test
    fun testPropertyAssignment() = runTest {
        eval("""
            class Box(var value: Object?)
            val b = Box(null)
            b.value ?= 10
            assertEquals(10, b.value)
            b.value ?= 20
            assertEquals(10, b.value)
        """.trimIndent())
    }

    @Test
    fun testIndexAssignment() = runTest {
        eval("""
            val a = [null, 1]
            a[0] ?= 10
            assertEquals(10, a[0])
            a[1] ?= 20
            assertEquals(1, a[1])
        """.trimIndent())
    }

    @Test
    fun testOptionalChaining() = runTest {
        eval("""
            class Inner(var value: Object?)
            class Outer(var inner: Inner?)
            
            var o: Outer? = null
            o?.inner?.value ?= 10 // should do nothing
            assertEquals(null, o)
            
            o = Outer(null)
            o?.inner?.value ?= 10 // should do nothing because inner is null
            val outer = o as Outer
            assertEquals(null, outer.inner)
            
            outer.inner = Inner(null)
            outer.inner?.value ?= 42
            assertEquals(42, (outer.inner as Inner).value)
            outer.inner?.value ?= 100
            assertEquals(42, (outer.inner as Inner).value)
        """.trimIndent())
    }

    @Test
    fun testDoubleEvaluation() = runTest {
        eval("""
            var count = 0
            fun getIdx() {
                count = count + 1
                return 0
            }
            
            val a = [null]
            a[getIdx()] ?= 10
            
            // Current behavior: double evaluation happens in ObjRef for compound ops
            // getIdx() is called once for checking if null, and once for setting if it was null.
            assertEquals(10, a[0])
            assertEquals(2, count)
            
            a[getIdx()] ?= 20
            // If it's NOT null, it only evaluates once to check the value.
            assertEquals(10, a[0])
            assertEquals(3, count)
        """.trimIndent())
    }
    
    @Test
    fun testReturnValue() = runTest {
        eval("""
            var x = null
            val r1 = (x ?= 42)
            assertEquals(42, r1)
            assertEquals(42, x)
            
            val r2 = (x ?= 100)
            assertEquals(42, r2)
            assertEquals(42, x)
        """.trimIndent())
    }
}
