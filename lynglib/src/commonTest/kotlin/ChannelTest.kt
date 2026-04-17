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

class ChannelTest {

    @Test
    fun testRendezvousChannel() = runTest {
        eval("""
            val ch = Channel()
            val producer = launch {
                ch.send(1)
                ch.send(2)
                ch.send(3)
                ch.close()
            }
            val results = []
            var v = ch.receive()
            while (v != null) {
                results += v
                v = ch.receive()
            }
            assertEquals([1, 2, 3], results)
        """.trimIndent())
    }

    @Test
    fun testBufferedChannel() = runTest {
        eval("""
            val ch = Channel(4)
            ch.send(10)
            ch.send(20)
            ch.send(30)
            ch.close()
            assertEquals(10, ch.receive())
            assertEquals(20, ch.receive())
            assertEquals(30, ch.receive())
            // closed and drained
            assertEquals(null, ch.receive())
        """.trimIndent())
    }

    @Test
    fun testTryReceive() = runTest {
        eval("""
            val ch = Channel(2)
            // nothing buffered yet
            assertEquals(null, ch.tryReceive())
            ch.send(42)
            assertEquals(42, ch.tryReceive())
            assertEquals(null, ch.tryReceive())
        """.trimIndent())
    }

    @Test
    fun testChannelProperties() = runTest {
        eval("""
            val ch = Channel(1)
            assert(!ch.isClosedForSend)
            assert(!ch.isClosedForReceive)
            ch.send(1)
            ch.close()
            assert(ch.isClosedForSend)
            // still has one buffered item, so not yet closed for receive
            assert(!ch.isClosedForReceive)
            ch.receive()
            // now fully drained
            assert(ch.isClosedForReceive)
        """.trimIndent())
    }

    @Test
    fun testUnlimitedChannel() = runTest {
        eval("""
            val ch = Channel(Channel.UNLIMITED)
            (1..100).forEach { ch.send(it) }
            ch.close()
            var sum = 0
            var v = ch.receive()
            while (v != null) {
                sum += v
                v = ch.receive()
            }
            assertEquals(5050, sum)
        """.trimIndent())
    }

    @Test
    fun testChannelProducerConsumerPattern() = runTest {
        eval("""
            val ch = Channel()
            val results = []
            val mu = Mutex()

            val consumer = launch {
                var item = ch.receive()
                while (item != null) {
                    mu.withLock { results += item }
                    item = ch.receive()
                }
            }

            launch {
                for (i in 1..5) {
                    ch.send("msg:${'$'}i")
                }
                ch.close()
            }.await()

            consumer.await()
            assertEquals(["msg:1","msg:2","msg:3","msg:4","msg:5"], results)
        """.trimIndent())
    }
}
