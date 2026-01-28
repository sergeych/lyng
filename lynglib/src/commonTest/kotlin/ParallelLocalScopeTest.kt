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

/*
 * Focused regression test for local variable visibility across suspension inside withLock { }
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Ignore
import kotlin.test.Test

@Ignore("TODO(bytecode-only): uses fallback")
class ParallelLocalScopeTest {

    @Test
    fun localsSurviveSuspensionInsideWithLock() = runTest {
        // Minimal reproduction of the pattern used in ScriptTest.testParallels2
        eval(
            """
            class AtomicCounter {
                private val m = Mutex()
                private var counter = 0

                fun increment() {
                    m.withLock {
                        val a = counter
                        delay(1)
                        counter = a + 1
                    }
                }

                fun getCounter() { counter }
            }

            val ac = AtomicCounter()
            // Single-threaded increments should work if locals survive after delay
            for (i in 1..3) ac.increment()
            assertEquals(3, ac.getCounter())
            """.trimIndent()
        )
    }
}
