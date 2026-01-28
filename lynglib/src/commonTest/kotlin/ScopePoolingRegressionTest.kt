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
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.eval
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopePoolingRegressionTest {

    @Test
    fun testPooledScopeInstance() = runTest {
        val saved = PerfFlags.SCOPE_POOL
        PerfFlags.SCOPE_POOL = true
        try {
            val result = eval("""
                class A {
                    fun test() {
                        // println is a global function
                        println("Calling println from A")
                        "method ok"
                    }
                }
                
                // Use a transient scope (lambda) to create the instance
                val creator = { A() }
                val a = creator()
                
                // Re-use the pool to ensure the scope used above is reset/repurposed
                val other = { 1 + 2 }
                other()
                other()
                
                // Now call method on 'a'. It should still find global 'println'
                a.test()
            """.trimIndent())
            assertEquals("method ok", result.toString())
        } finally {
            PerfFlags.SCOPE_POOL = saved
        }
    }
}
