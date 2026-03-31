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
import net.sergeych.lyng.obj.ObjBool
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalRealMemberInferenceRegressionTest {

    @Test
    fun sqrtInitializedLocalVarKeepsRealReceiverTypeForMembers() = runTest {
        val result = eval(
            """
            fun probe(T: Real, c: Real, g: Real): Bool {
                fun passthrough(h: Real): Real = h / c

                val term = 1.0 + g * T / c
                val sqrtTerm = sqrt(1.0 + 2.0 * g * T / c)
                assert(sqrtTerm is Real)
                assert(!sqrtTerm.isNaN())
                var h = (c * c / g) * (term - sqrtTerm)
                assert(passthrough(h) >= 0.0)
                assert(h is Real)
                !h.isNaN()
            }

            probe(6.0, 340.0, 9.81)
            """.trimIndent()
        )

        assertEquals(ObjBool(true), result)
    }
}
