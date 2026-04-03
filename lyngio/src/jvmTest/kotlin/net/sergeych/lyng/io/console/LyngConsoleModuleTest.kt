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

package net.sergeych.lyng.io.console

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjIllegalOperationException
import net.sergeych.lyngio.console.*
import net.sergeych.lyngio.console.security.ConsoleAccessOp
import net.sergeych.lyngio.console.security.ConsoleAccessPolicy
import net.sergeych.lyngio.console.security.PermitAllConsoleAccessPolicy
import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision
import kotlin.test.*

class LyngConsoleModuleTest {

    private fun newScope(): Scope = Scope.new()

    @Test
    fun installIsIdempotent() = runBlocking {
        val scope = newScope()
        assertTrue(createConsoleModule(PermitAllConsoleAccessPolicy, scope))
        assertFalse(createConsoleModule(PermitAllConsoleAccessPolicy, scope))
    }

    @Test
    fun moduleSmokeScript() = runBlocking {
        val scope = newScope()
        createConsoleModule(PermitAllConsoleAccessPolicy, scope)

        val code = """
            import lyng.io.console
            import lyng.stdlib

            val d = Console.details()
            assert(d.supported is Bool)
            assert(d.isTty is Bool)
            assert(d.ansiLevel is ConsoleAnsiLevel)

            val g = Console.geometry()
            if (g != null) {
                assert(g.columns is Int)
                assert(g.rows is Int)
                assert(g.columns > 0)
                assert(g.rows > 0)
            }

            assert(Console.events() is Iterable)
            Console.write("")
            Console.flush()
            Console.home()
            Console.clear()
            Console.moveTo(1, 1)
            Console.clearLine()
            Console.enterAltScreen()
            Console.leaveAltScreen()
            Console.setCursorVisible(true)

            val changed = Console.setRawMode(false)
            assert(changed is Bool)
            true
        """.trimIndent()

        val result = scope.eval(code)
        assertIs<ObjBool>(result)
        assertTrue(result.value)
    }

    @Test
    fun denyWritePolicyMapsToIllegalOperation() {
        runBlocking {
        val denyWritePolicy = object : ConsoleAccessPolicy {
            override suspend fun check(op: ConsoleAccessOp, ctx: AccessContext): AccessDecision = when (op) {
                is ConsoleAccessOp.WriteText -> AccessDecision(Decision.Deny, "denied by test policy")
                else -> AccessDecision(Decision.Allow)
            }
        }

        val scope = newScope()
        createConsoleModule(denyWritePolicy, scope)

        val error = kotlin.test.assertFailsWith<ExecutionError> {
            scope.eval(
                """
                import lyng.io.console
                Console.write("x")
                """.trimIndent()
            )
        }

        assertIs<ObjIllegalOperationException>(error.errorObject)
        }
    }

    @Test
    fun eventsIteratorRecoversAfterSourceFailure() = runBlocking {
        val scope = newScope()
        var eventsCalls = 0
        val console = object : LyngConsole {
            override val isSupported: Boolean = true

            override suspend fun isTty(): Boolean = true

            override suspend fun geometry(): ConsoleGeometry? = null

            override suspend fun ansiLevel(): ConsoleAnsiLevel = ConsoleAnsiLevel.NONE

            override suspend fun write(text: String) {}

            override suspend fun flush() {}

            override fun events(): ConsoleEventSource {
                eventsCalls += 1
                val callNo = eventsCalls
                return object : ConsoleEventSource {
                    private var emitted = false

                    override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? {
                        if (emitted) return null
                        emitted = true
                        return when (callNo) {
                            1 -> throw IllegalStateException("synthetic source failure")
                            else -> ConsoleEvent.KeyDown(key = "x")
                        }
                    }

                    override suspend fun close() {}
                }
            }

            override suspend fun setRawMode(enabled: Boolean): Boolean = enabled
        }

        assertTrue(createConsoleModule(PermitAllConsoleAccessPolicy, scope.importManager, console))

        val result = scope.eval(
            """
            import lyng.io.console

            val it = Console.events().iterator()
            assert(it.hasNext())
            val ev = it.next()
            assert(ev is ConsoleKeyEvent)
            assert((ev as ConsoleKeyEvent).key == "x")
            true
            """.trimIndent()
        )

        assertIs<ObjBool>(result)
        assertTrue(result.value)
        assertEquals(2, eventsCalls)
    }
}
