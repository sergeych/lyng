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

class StdlibTest {
    @Test
    fun testIterableFilter() = runTest {
        eval("""
            assertEquals([2,4,6,8], (1..8).filter{ println("call2"); it % 2 == 0 }.toList() )
            println("-------------------")
            assertEquals([1,3,5,7], (1..8).filter{ println("call1"); it % 2 == 1 }.toList() )
        """.trimIndent())

    }

    @Test
    fun testFirstLast() = runTest {
        eval("""
            assertEquals(1, (1..8).first )
            assertEquals(8, (1..8).last )
        """.trimIndent())
    }

    @Test
    fun testTake() = runTest {
        eval("""
            val r = 1..8
            assertEquals([1,2,3], r.take(3).toList() )
            assertEquals([7,8], r.takeLast(2).toList() )
        """.trimIndent())
    }

    @Test
    fun testAnyAndAll() = runTest {
        eval("""
            assert( [1,2,3].any { it > 2 } )
            assert( ![1,2,3].any { it > 4 } )
            assert( [1,2,3].all { it <= 3 } )
            
        """.trimIndent())
    }

    @Test
    fun testRingBuffer() = runTest {
        eval("""
            val r = RingBuffer(3)
            assert( r is RingBuffer )
            assertEquals(0, r.size)
            assertEquals(3, r.capacity)
            
            r += 10
            assertEquals(1, r.size)
            assertEquals(10, r.first)
            
            r += 20
            assertEquals(2, r.size)
            assertEquals( [10, 20], r.toList() )
            
            r += 30
            assertEquals(3, r.size)
            assertEquals( [10, 20, 30], r.toList() )
            
            r += 40
            assertEquals(3, r.size)
            assertEquals( [20, 30, 40], r.toList() )
            
        """.trimIndent())
    }

    @Test
    fun testDrop() = runTest {
        eval("""
            assertEquals([7,8], (1..8).drop(6).toList() )
            assertEquals([1,2], (1..8).dropLast(6).toList() )
        """.trimIndent())
    }

    @Test
    fun testFlattenAndFilter() = runTest {
        eval("""
            assertEquals([1,2,3,4,5,6], [1,3,5].map { [it, it+1] }.flatten() )
            assertEquals([1,3,5], [null,1,null, 3,5].filterNotNull().toList())
        """)
    }

    @Test
    fun testFlatMap() = runTest {
        eval("""
            assertEquals([1,2,3,4,5,6], [1,3,5].flatMap { [it,it+1] }.toList() )
        """)
    }

    @Test
    fun testCount() = runTest {
        eval("""
            assertEquals(5, (1..10).toList().count { it % 2 == 1 } )
        """)
    }

    @Test
    fun testWith() = runTest {
        eval("""
            class Person(val name, var age)
            val p = Person("Alice", 30)
            
            val result = with(p) {
                assertEquals("Alice", name)
                assertEquals(30, age)
                age = 31
                "done"
            }
            
            assertEquals("done", result)
            assertEquals(31, p.age)
        """.trimIndent())
    }
}