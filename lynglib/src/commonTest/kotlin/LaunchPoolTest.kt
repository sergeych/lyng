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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import net.sergeych.lyng.eval as lyngEval

class LaunchPoolTest {

    private suspend fun eval(code: String) = withContext(Dispatchers.Default) {
        withTimeout(2_000L) { lyngEval(code) }
    }

    @Test
    fun testBasicExecution() = runTest {
        eval("""
            val pool = LaunchPool(2)
            val d1 = pool.launch { 1 + 1 }
            val d2 = pool.launch { "hello" }
            pool.closeAndJoin()
            assertEquals(2, d1.await())
            assertEquals("hello", d2.await())
        """.trimIndent())
    }

    @Test
    fun testResultsCollected() = runTest {
        eval("""
            val pool = LaunchPool(4)
            val jobs = (1..10).map { n -> pool.launch { n * n } }
            pool.closeAndJoin()
            val results = jobs.map { (it as Deferred).await() }
            assertEquals([1,4,9,16,25,36,49,64,81,100], results)
        """.trimIndent())
    }

    @Test
    fun testConcurrencyLimit() = runTest {
        eval("""
            // With maxWorkers=2, at most 2 tasks run at the same time.
            val mu = Mutex()
            var active = 0
            var maxSeen = 0

            val pool = LaunchPool(2)
            val jobs = (1..8).map {
                pool.launch {
                    mu.withLock { active++; if (active > maxSeen) maxSeen = active }
                    delay(5)
                    mu.withLock { active-- }
                }
            }
            pool.closeAndJoin()

            assert(maxSeen <= 2) { "maxSeen was " + maxSeen + ", expected <= 2" }
        """.trimIndent())
    }

    @Test
    fun testExceptionCapturedInDeferred() = runTest {
        eval("""
            val pool = LaunchPool(2)
            val good = pool.launch { 42 }
            val bad  = pool.launch { throw IllegalArgumentException("boom") }
            pool.closeAndJoin()

            assertEquals(42, good.await())
            assertThrows(IllegalArgumentException) { bad.await() }
        """.trimIndent())
    }

    @Test
    fun testPoolContinuesAfterLambdaException() = runTest {
        eval("""
            val pool = LaunchPool(1)
            val bad  = pool.launch { throw IllegalArgumentException("fail") }
            val good = pool.launch { "ok" }
            pool.closeAndJoin()

            assertThrows(IllegalArgumentException) { bad.await() }
            assertEquals("ok", good.await())
        """.trimIndent())
    }

    @Test
    fun testLaunchAfterCloseAndJoinThrows() = runTest {
        eval("""
            val pool = LaunchPool(2)
            pool.launch { 1 }
            pool.closeAndJoin()

            assertThrows(IllegalStateException) { pool.launch { 2 } }
        """.trimIndent())
    }

    @Test
    fun testLaunchAfterCancelThrows() = runTest {
        eval("""
            val pool = LaunchPool(2)
            pool.cancel()

            assertThrows(IllegalStateException) { pool.launch { 1 } }
        """.trimIndent())
    }

    @Test
    fun testCancelAndJoinWaitsForWorkers() = runTest {
        eval("""
            val pool = LaunchPool(2)
            pool.launch { delay(5) }
            pool.cancelAndJoin()
            // pool is now closed — further launches must throw
            assertThrows(IllegalStateException) { pool.launch { 1 } }
        """.trimIndent())
    }

    @Test
    fun testCloseAndJoinDrainsQueue() = runTest {
        eval("""
            val mu = Mutex()
            val results = []
            val pool = LaunchPool(1)   // single worker to force sequential execution

            (1..5).forEach { n ->
                pool.launch {
                    delay(1)
                    mu.withLock { results += n }
                }
            }
            pool.closeAndJoin()   // waits for all 5 to complete

            assertEquals(5, results.size)
        """.trimIndent())
    }

    @Test
    fun testBoundedQueueSuspendsProducer() = runTest {
        eval("""
            // queue of 2 + 1 worker; producer can only be 1 ahead of what's running
            val pool = LaunchPool(1, 2)
            val order = []
            val mu = Mutex()

            // fill the queue
            val d1 = pool.launch { delay(5); mu.withLock { order += 1 } }
            val d2 = pool.launch { delay(3); mu.withLock { order += 2 } }
            val d3 = pool.launch { delay(3); mu.withLock { order += 3 } }

            pool.closeAndJoin()
            assertEquals(3, order.size)
        """.trimIndent())
    }

    @Test
    fun testUnlimitedQueueDefault() = runTest {
        eval("""
            val pool = LaunchPool(4)
            val jobs = (1..50).map { n -> pool.launch { n } }
            pool.closeAndJoin()
            var sum = 0
            for (j in jobs) { sum += (j as Deferred).await() }
            assertEquals(1275, sum)   // 1+2+...+50
        """.trimIndent())
    }

    @Test
    fun testIdempotentClose() = runTest {
        eval("""
            val pool = LaunchPool(2)
            pool.closeAndJoin()
            pool.closeAndJoin()   // calling again must not throw
            pool.cancel()         // mixing close calls must not throw either
        """.trimIndent())
    }

}
