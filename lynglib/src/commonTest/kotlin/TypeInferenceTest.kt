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

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.eval
import kotlin.test.Test

/**
 * Regression tests for type inference of class fields accessed inside nested closures.
 *
 * A class field declared as `val foo = SomeType(...)` should have its type inferred as
 * `SomeType` everywhere inside the class body, including inside lambdas and closures
 * that capture the field via the implicit `this` receiver.
 */
class TypeInferenceTest {

    /** Channel field type inferred from constructor — accessed in a launch closure */
    @Test
    fun testChannelFieldInLaunchClosure() = runBlocking<Unit> {
        eval("""
            class Foo {
                private val ch = Channel(Channel.UNLIMITED)
                private val worker = launch {
                    var item = ch.receive()
                    while (item != null) {
                        item = ch.receive()
                    }
                }
                fun start() {
                    ch.send(1)
                    ch.close()
                    (worker as Deferred).await()
                }
            }
            Foo().start()
        """.trimIndent())
    }

    /** Mutex field type inferred from constructor — used directly in a method body */
    @Test
    fun testMutexFieldDirectUse() = runBlocking<Unit> {
        eval("""
            class Bar {
                private val mu = Mutex()
                private var count = 0
                fun inc() { mu.withLock { count++ } }
                fun get() { count }
            }
            val b = Bar()
            b.inc()
            b.inc()
            assertEquals(2, b.get())
        """.trimIndent())
    }

    /** CompletableDeferred field type inferred — complete/await used directly */
    @Test
    fun testCompletableDeferredFieldDirectUse() = runBlocking<Unit> {
        eval("""
            class Baz {
                private val d = CompletableDeferred()
                fun complete(v) { d.complete(v) }
                fun result() { (d as Deferred).await() }
            }
            val baz = Baz()
            launch { baz.complete(42) }
            assertEquals(42, baz.result())
        """.trimIndent())
    }

    /** Channel field accessed inside a map closure within class initializer */
    @Test
    fun testChannelFieldInMapAndLaunchClosure() = runBlocking<Unit> {
        eval("""
            class Pool(n) {
                private val ch = Channel(Channel.UNLIMITED)
                private val workers = (1..n).map {
                    launch {
                        var item = ch.receive()
                        while (item != null) {
                            item = ch.receive()
                        }
                    }
                }
                fun closeAll() {
                    ch.close()
                    for (w in workers) { (w as Deferred).await() }
                }
            }
            Pool(2).closeAll()
        """.trimIndent())
    }
}
