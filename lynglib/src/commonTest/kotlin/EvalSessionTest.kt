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

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjFlow
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import kotlin.test.*

class EvalSessionTest {
    @Test
    fun sessionCreatesAndReusesScope() = runTest {
        val session = EvalSession()

        assertEquals(null, session.scope)
        assertEquals(10L, (session.eval("var x = 10; x") as ObjInt).value)
        assertNotNull(session.scope)
        assertEquals(session.scope, session.getScope())
        assertEquals(15L, (session.eval("x += 5; x") as ObjInt).value)
    }

    @Test
    fun sessionCancelStopsLaunchedCoroutines() = runTest {
        val scope = Script.newScope()
        scope.eval("var exportedVal=0")
        val session = EvalSession(scope)

        session.eval(
            """
            var touched = false
            launch {
                while(true) {
                    delay(100)
                    touched = true
                }
            }
            """
                .trimIndent()
        )

        session.cancelAndJoin()
        advanceTimeBy(150)

        assertFalse((scope.eval("touched") as ObjBool).value)
    }

    @Test
    fun cancelAndJoinStopsLaunchedCoroutinesForUserProvidedScriptScope() = runTest {
        val scope = Script.newScope()
        val session = EvalSession(scope)

        session.eval(
            """
            var counter = 0
            launch {
                delay(10)
                counter += 1
            }
            """
                .trimIndent()
        )

        session.cancelAndJoin()
        advanceTimeBy(20)

        assertEquals(0L, (scope.eval("counter") as ObjInt).value)
    }

    @Test
    fun joinObservesWorkStartedByLaterEval() = runTest {
        val session = EvalSession(Scope())

        session.eval("launch { delay(100) }")

        var joined = false
        val waiter = launch {
            session.join()
            joined = true
        }

        advanceTimeBy(50)
        assertFalse(joined)

        session.eval("launch { delay(100) }")

        advanceTimeBy(60)
        assertFalse(joined)

        advanceTimeBy(50)
        waiter.join()
        assertTrue(joined)
    }

    @Test
    fun concurrentEvalCallsAreSerialized() = runTest {
        val session = EvalSession(Scope())

        session.eval("var counter = 0")

        val first = async {
            session.eval(
                """
                delay(100)
                counter += 1
                counter
                """
                    .trimIndent()
            ) as ObjInt
        }
        val second = async {
            session.eval(
                """
                counter += 10
                counter
                """
                    .trimIndent()
            ) as ObjInt
        }

        advanceTimeBy(150)

        assertEquals(1L, first.await().value)
        assertEquals(11L, second.await().value)
        assertEquals(11L, (session.eval("counter") as ObjInt).value)
    }

    @Test
    fun joinWaitsForActiveFlowProducer() = runTest {
        val scope = Scope()
        val session = EvalSession(scope)
        val flow = session.eval(
            """
            flow {
                delay(100)
                emit(1)
                delay(100)
                emit(2)
            }
            """
                .trimIndent()
        ) as ObjFlow

        var joined = false
        var collected: ObjList? = null

        val collector = launch {
            collected = flow.callMethod(scope, "toList")
        }
        val waiter = launch {
            session.join()
            joined = true
        }

        advanceTimeBy(150)
        assertFalse(joined)

        advanceTimeBy(100)
        collector.join()
        waiter.join()

        assertTrue(joined)
        assertEquals(listOf(1L, 2L), collected!!.list.map { (it as ObjInt).value })
    }
}
