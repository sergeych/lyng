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

package net.sergeych.lyng.miniast

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.binding.Binder
import kotlin.test.Test
import kotlin.test.assertEquals

class ParamTypeInferenceTest {

    @Test
    fun testParameterTypeInference() = runTest {
        val code = """
            class A {
                fun foo(p: String) {
                    p.
                }
            }
            
            fun bar(q: Int) {
                q.
            }
        """.trimIndent()

        val sink = MiniAstBuilder()
        Compiler.compileWithMini(code.trimIndent(), sink)
        val mini = sink.build()!!
        val binding = Binder.bind(code, mini)

        val dotPosQ = code.indexOf("q.") + 1
        val receiverClassQ = DocLookupUtils.guessReceiverClassViaMini(mini, code, dotPosQ, listOf("lyng.stdlib"), binding)
        assertEquals("Int", receiverClassQ, "Should infer type of parameter q in top-level function")

        val dotPosP = code.indexOf("p.") + 1
        val receiverClassP = DocLookupUtils.guessReceiverClassViaMini(mini, code, dotPosP, listOf("lyng.stdlib"), binding)
        assertEquals("String", receiverClassP, "Should infer type of parameter p in member function")
    }
}
