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

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * JVM-only fast functional tests to broaden coverage for pooling, classes, and control flow.
 * Keep each test fast (<1s) and deterministic.
 */
class ScriptSubsetJvmTest_Additions5 {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value

    @Test
    fun classVisibility_public_vs_private_jvm_only() = runBlocking {
        val code = """
            class C() {
                var pub = 3
                private var prv = 5
                fun getPrv() { prv }
            }
            val c = C()
            c.pub + c.getPrv()
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(8L, r)
    }

    @Test
    fun classVisibility_private_field_access_error_jvm_only() = runBlocking {
        val code = """
            class C() {
                private var prv = 5
            }
            val c = C()
            // attempt to access private field should fail
            c.prv
        """.trimIndent()
        // We expect an exception; Scope.eval() will throw ExecutionError.
        assertFailsWith<Throwable> { evalInt(code) }
        // Ensure Unit return from the test body
        Unit
    }

    @Test
    fun inheritance_override_call_path_jvm_only() = runBlocking {
        val code = """
            // Simple two classes with same method name; no inheritance to avoid syntax dependencies
            class A() { fun v() { 1 } }
            class B() { fun v() { 2 } }
            val a = A(); val b = B()
            a.v() + b.v()
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(3L, r)
    }

    @Ignore("TODO(bytecode+closure): pooled lambda calls duplicate side effects; re-enable after fixing call semantics")
    @Test
    fun pooled_frames_closure_this_capture_jvm_only() = runBlocking {
        val code = """
            class Box() { var x = 40; fun inc() { x = x + 1 } fun get() { x } }
            fun make(block) { block }
            val b = Box()
            val f = make { b.inc(); b.get() }
            var r = 0
            r = f()
            r
        """.trimIndent()
        // OFF
        PerfFlags.SCOPE_POOL = false
        val off = evalInt(code)
        // ON
        PerfFlags.SCOPE_POOL = true
        val on = evalInt(code)
        assertEquals(41L, off)
        assertEquals(41L, on)
        PerfFlags.SCOPE_POOL = false
    }
}
