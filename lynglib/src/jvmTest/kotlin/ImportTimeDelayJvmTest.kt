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
import kotlinx.coroutines.withTimeout
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportTimeDelayJvmTest {

    @Test
    fun importingTimeKeepsIntDelayInMilliseconds() = runBlocking {
        val result = withTimeout(1_000) {
            Script.newScope().eval(
                """
                import lyng.time

                var completed = 0

                (1..3).map {
                    launch {
                        delay(50)
                    }
                }.forEach {
                    (it as Deferred).await()
                    completed += 1
                }

                completed
                """.trimIndent()
            )
        }

        assertEquals(3L, (result as ObjInt).value)
    }
}
