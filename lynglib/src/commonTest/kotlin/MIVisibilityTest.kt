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
import net.sergeych.lyng.eval
import kotlin.test.Test
import kotlin.test.assertFails

class MIVisibilityTest {

    @Test
    fun privateMethodNotVisibleInSubclass() = runTest {
        val code = """
            class Foo() {
                private fun secret() { "S" }
            }
            class Bar() : Foo() {
                fun trySecret() { this@Foo.secret() }
            }
            val b = Bar()
            // calling a wrapper that tries to access Foo.secret must fail
            b.trySecret()
        """.trimIndent()
        assertFails { eval(code) }
    }

    @Test
    fun protectedMethodNotVisibleFromUnrelatedEvenViaCast() = runTest {
        val code = """
            class Foo() { protected fun prot() { "P" } }
            class Bar() : Foo() { }
            val b = Bar()
            // Unrelated context tries to call through cast — should fail
            (b as Foo).prot()
        """.trimIndent()
        assertFails { eval(code) }
    }

    @Test
    fun privateFieldNotVisibleInSubclass() = runTest {
        val code = """
            class Foo() { private val x = "X" }
            class Bar() : Foo() { fun getX() { this@Foo.x } }
            val b = Bar()
            b.getX()
        """.trimIndent()
        assertFails { eval(code) }
    }

    @Test
    fun protectedFieldNotVisibleFromUnrelatedEvenViaCast() = runTest {
        // Not allowed from unrelated, even via cast
        val code = """
            class Foo() { protected val y = "Y" }
            class Bar() : Foo() { }
            val b = Bar()
            (b as Foo).y
        """.trimIndent()
        assertFails { eval(code) }
    }
}
