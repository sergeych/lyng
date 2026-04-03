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
package net.sergeych

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjList
import net.sergeych.lyng.obj.ObjString
import org.junit.Test
import kotlin.test.assertNotEquals

class CliDispatcherJvmTest {
    @Test
    fun executeSourceRunsOnDefaultDispatcher() = runBlocking {
        val callerThread = Thread.currentThread()
        val callerThreadKey = "${System.identityHashCode(callerThread)}:${callerThread.name}"
        val scope = Script.newScope().apply {
            addFn("threadKey") { ObjString("${System.identityHashCode(Thread.currentThread())}:${Thread.currentThread().name}") }
            addFn("threadName") { ObjString(Thread.currentThread().name) }
        }
        val session = EvalSession(scope)

        try {
            val result = evalOnCliDispatcher(
                session,
                Source(
                    "<test>",
                    """
                    val task = launch { [threadKey(), threadName()] }
                    val child = task.await()
                    [threadKey(), threadName(), child]
                    """.trimIndent()
                )
            ) as ObjList

            val topLevelThreadKey = (result.list[0] as ObjString).value
            val topLevelThreadName = (result.list[1] as ObjString).value
            val child = result.list[2] as ObjList
            val childThreadKey = (child.list[0] as ObjString).value
            val childThreadName = (child.list[1] as ObjString).value

            assertNotEquals(
                callerThreadKey,
                topLevelThreadKey,
                "CLI top-level script body should not run on the runBlocking caller thread: $topLevelThreadName"
            )
            assertNotEquals(
                callerThreadKey,
                childThreadKey,
                "CLI launch child should not inherit the runBlocking caller thread: $childThreadName"
            )
        } finally {
            session.cancelAndJoin()
        }
    }
}
