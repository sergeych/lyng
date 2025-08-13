/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

class TestCoroutines {

    @Test
    fun testLaunch() = runTest {
        eval(
            """
            var passed = false
            val x = launch { 
                delay(10)
                passed = true
                "ok"
            }
            assert(!passed)
            assertEquals( x.await(), "ok")
            assert(passed)
            assert(x.isCompleted)
        """.trimIndent()
        )
    }

    @Test
    fun testCompletableDeferred() = runTest {
        eval(
            """
            val done = CompletableDeferred()
            
            launch { 
                delay(30)
                done.complete("ok")
            }
            
            assert(!done.isCompleted)
            assert(done.isActive)
            assertEquals( done.await(), "ok")
            assert(done.isCompleted)
        """.trimIndent()
        )
    }

    @Test
    fun testMutex() = runTest {
        eval(
            """
            var counter = 0
            val mutex = Mutex()
            
            (1..4).map { 
                launch {
//                    mutex.withLock {
                        val c = counter
                        delay(5)
                        counter = c + 1
//                    }
                }
             }.forEach { it.await() }
             println(counter)
             assert( counter < 10 )
             
    """.trimIndent()
        )
    }

    @Test
    fun testFlows() = runTest {
        eval("""
            val f = flow {
                println("Starting generator")
                var n1 = 0
                var n2 = 1
                emit(n1)
                emit(n2)
                while(true) {
                    val n = n1 + n2
                    emit(n)
                    n1 = n2
                    n2 = n
                }
            }
            val correctFibs = [0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765]
            assertEquals( correctFibs, f.take(correctFibs.size))
            
        """.trimIndent())
    }

    @Test
    fun testFlow2() = runTest {
        eval("""
                val f = flow {
                        emit("start")
                        emit("start2")
                        (1..4).forEach { 
                            emit(it) 
                        }
                }
                // let's collect flow:
                val result = []
                for( x in f ) result += x
                    println(result)
                
                // let's collect it once again:
                assertEquals( result, f.toList())
                assertEquals( result, f.toList())
        """.trimIndent())
    }

    @Test
    fun testFlowClosures() = runTest {
        eval("""
            fun filter( a, b ) {
                println("filter: %s, %s"(a,b))
                flow {
                    emit(a)
                    emit(b)
                }
            }
            
            assertEquals( [5, 1], filter(5,1).toList() )
            assertEquals( [2, 3], filter(2,3).toList() )
            
        """.trimIndent())
    }

    @Test
    fun testFilterFlow() = runTest {
        eval("""
            fun filter( list, predicate ) {
                val p = predicate
                println("predicate "+predicate+" / "+p)
                flow {
                    // here p is captured only once and does not change!
                    for( item in list ) {
                        print("filter "+p+" "+item+": ")
                        if( p(item) ) {
                            println("OK")
                            emit(item)
                        }
                        else println("NO")
                    }
                }
            }
            
//            fun drop(i, n) {
//                require( n >= 0, "drop amount must be non-negative")
//                var count = 0
//                println("drop %d"(n))
//                filter(i) {
//                    count++ >= n
//                }
//            }
            
            val src = (1..1).toList()
            assertEquals( 1, filter(src) { true }.toList().size )
            println("----------------------------------------------------------")
            println("----------------------------------------------------------")
            println("----------------------------------------------------------")
            println("----------------------------------------------------------")
            assertEquals( 0, filter(src) { false }.toList().size )
//            assertEquals( 3, filter(src) { true }.size() ) 
            
//            assertEquals( [7,8], drop((1..8).toList(),6).toList())
//            assertEquals( [1,3,5,7], filter((1..8).toList()) { 
//                println("call2")
//                it % 2 == 1 
//            }.toList())
        """.trimIndent())
    }
}