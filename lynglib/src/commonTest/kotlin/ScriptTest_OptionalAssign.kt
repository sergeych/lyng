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
 * Tests for optional chaining assignment semantics (no-op on null receiver)
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class ScriptTest_OptionalAssign {

    @Test
    fun optionalFieldAssignIsNoOp() = runTest {
        eval(
            """
            class C { var x = 1 }
            var c = null
            // should be no-op and not throw
            c?.x = 5
            assertEquals(null, c?.x)
            // non-null receiver should work as usual
            c = C()
            c?.x = 7
            assertEquals(7, c.x)
            """.trimIndent()
        )
    }

    @Test
    fun optionalIndexAssignIsNoOp() = runTest {
        eval(
            """
            var a = null
            // should be no-op and not throw
            a?[0] = 42
            assertEquals(null, a?[0])
            // non-null receiver should work as usual
            a = [1,2,3]
            a?[1] = 99
            assertEquals(99, a[1])
            """.trimIndent()
        )
    }
}
