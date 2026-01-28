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
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-only fast functional subset additions. Keep each test quick (< ~1s) and deterministic.
 */
@Ignore("TODO(bytecode-only): uses fallback (when/try)")
class ScriptSubsetJvmTest_Additions3 {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value
    private suspend fun evalBool(code: String): Boolean = (Scope().eval(code) as ObjBool).value
    private suspend fun evalList(code: String): List<Any?> = (Scope().eval(code) as ObjList).list.map { (it as? ObjInt)?.value ?: it }

    @Test
    fun controlFlow_when_and_ifElse_jvm_only() = runBlocking {
        val code = """
            fun classify(x) {
                when(x) {
                    0 -> 100
                    1 -> 200
                    else -> 300
                }
            }
            val a = classify(0)
            val b = classify(1)
            val c = classify(5)
            if (true) 1 else 2
            a + b + c
        """.trimIndent()
        val r = evalInt(code)
        // 100 + 200 + 300 = 600
        assertEquals(600L, r)
    }

    @Test
    fun optionals_chain_field_index_method_jvm_only() = runBlocking {
        val code = """
            class Box() {
                var xs = [10,20,30]
                fun get(i) { xs[i] }
            }
            val maybe = null
            val b = Box()
            // optional on null yields null
            val r1 = maybe?.xs
            // optional on non-null: method and index
            val r2 = b?.get(1)
            r2
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(20L, r)
    }

    @Test
    fun exceptions_try_catch_finally_jvm_only() = runBlocking {
        val code = """
            fun risky(x) { if (x == 0) throw "boom" else 7 }
            var s = 0
            try { s = risky(0) } catch (e) { s = 1 } finally { s = s + 2 }
            s
        """.trimIndent()
        val r = evalInt(code)
        // catch sets 1, finally adds 2 -> 3
        assertEquals(3L, r)
    }

    @Test
    fun classes_visibility_and_fields_jvm_only() = runBlocking {
        val code = """
            class C() {
                var pub = 1
                private var hidden = 9
                fun getPub() { pub }
                fun getHidden() { hidden }
                fun setPub(v) { pub = v }
            }
            val c = C()
            c.setPub(5)
            c.getPub() + c.getHidden()
        """.trimIndent()
        val r = evalInt(code)
        // 5 + 9
        assertEquals(14L, r)
    }

    @Test
    fun collections_insert_remove_and_maps_jvm_only() = runBlocking {
        val code = """
            val lst = []
            lst.insertAt(0, 2)
            lst.insertAt(0, 1)
            lst.removeAt(1)
            // now [1]
            val a = 10
            val b = 20
            a + b + lst[0]
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(31L, r)
    }

    @Test
    fun loops_for_and_while_basics_jvm_only() = runBlocking {
        val n = 2000
        val code = """
            var s = 0
            for (i in 0..$n) { s = s + 1 }
            var j = 0
            while (j < $n) { s = s + 1; j = j + 1 }
            s
        """.trimIndent()
        val r = evalInt(code)
        // for loop adds n+1, while adds n -> total 2n+1
        assertEquals((2L*n + 1L), r)
    }

    @Test
    fun pooling_edgecase_captures_and_exception_jvm_only() = runBlocking {
        // Ensure pooling ON for this test, then restore default
        val prev = PerfFlags.SCOPE_POOL
        try {
            PerfFlags.SCOPE_POOL = true
            val code = """
                fun outer(a) {
                    fun inner(b) { if (b == 0) throw "err" else a + b }
                    try { inner(0) } catch (e) { a + 2 }
                }
                outer(5)
            """.trimIndent()
            val r = evalInt(code)
            assertEquals(7L, r)
        } finally {
            PerfFlags.SCOPE_POOL = prev
        }
    }
}
