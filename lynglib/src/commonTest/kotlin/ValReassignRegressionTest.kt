/*
 * Copyright 2026 Sergey S. Chernov
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
import kotlin.test.Ignore
import kotlin.test.Test

class ValReassignRegressionTest {

    @Test
    fun reassign_ctor_param_field_should_work() = runTest {
        eval(
            """
            class Wallet(balance = 0) {
                fun add(amount) {
                    balance += amount
                }
                fun transfer(amount) {
                    val balance = 0
                    add(amount)
                }
                fun get() { balance }
            }
            val w = Wallet()
            w.transfer(1)
            assertEquals(1, w.get())
            """.trimIndent()
        )
    }

    @Test
    fun reassign_field_should_not_see_caller_locals() = runTest {
        eval(
            """
            class Wallet(balance = 0) {
                fun add(amount) { balance += amount }
                fun get() { balance }
            }
            fun doTransfer(w, amount) {
                val balance = 0
                w.add(amount)
            }
            val w = Wallet()
            doTransfer(w, 2)
            assertEquals(2, w.get())
            """.trimIndent()
        )
    }

    @Test
    fun reassign_field_should_not_see_caller_param() = runTest {
        eval(
            """
            class Wallet(balance = 0) {
                fun add(amount) { balance += amount }
                fun get() { balance }
            }
            fun doTransfer(balance, w, amount) {
                w.add(amount)
            }
            val w = Wallet()
            doTransfer(0, w, 3)
            assertEquals(3, w.get())
            """.trimIndent()
        )
    }

    @Test
    fun reassign_field_should_not_see_block_local() = runTest {
        eval(
            """
            class Wallet(balance = 0) {
                fun add(amount) { balance += amount }
                fun get() { balance }
            }
            val w = Wallet()
            run {
                val balance = 0
                w.add(4)
            }
            assertEquals(4, w.get())
            """.trimIndent()
        )
    }
}
