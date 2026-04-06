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
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BitwiseTest {
    @Test
    fun bitwiseOperators_Int() = runTest {
        suspend fun e(src: String) = eval(src)

        assertEquals(ObjInt(1), e("5 & 3"))
        assertEquals(ObjInt(7), e("5 | 3"))
        assertEquals(ObjInt(6), e("5 ^ 3"))
        assertEquals(ObjInt(-1), e("~0"))

        assertEquals(ObjInt(8), e("1 << 3"))
        assertEquals(ObjInt(1), e("8 >> 3"))

        // shift count masking (like JVM): 65 -> 1
        assertEquals(ObjInt(2), e("1 << 65"))

        // precedence: additive tighter than shifts
        assertEquals(ObjInt(24), e("1 + 2 << 3"))

        // precedence chain: & tighter than ^ tighter than |
        assertEquals(ObjInt(3), e("1 | 2 ^ 3 & 1"))

        // type mismatch should raise
        assertFails { e("1 & 2.0") }
    }

    @Test
    fun testBitwiseInference() = runTest {
        eval(
            """
                import lyng.buffer
                class Foo() {
                    val buf = Buffer(64).toMutable()
                    fn fn2(): Int {
                        val tmp = this.buf[1] & 127
                        println("fn2: ", tmp)
                        tmp
                    }
                }
                
                val foo = Foo()
                assertEquals(0, foo.fn2())
                """
        )
    }

    @Test
    fun testCustomIndexerIntInference() = runTest {
        eval(
            """
                class TestBuffer() {
                    override fn getAt(index): Int = index + 1
                }

                class Foo() {
                    val buf = TestBuffer()
                    fn fn2(): Int {
                        val tmp = (this.buf[1] & 127) + this.buf[2] * 2 - 1
                        tmp
                    }
                }

                val foo = Foo()
                assertEquals(7, foo.fn2())
                """
        )
    }
}
