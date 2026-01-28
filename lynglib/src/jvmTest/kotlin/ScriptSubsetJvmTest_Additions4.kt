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
import net.sergeych.lyng.obj.ObjList
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * More JVM-only fast functional tests migrated from ScriptTest to avoid MPP runs.
 * Keep each test fast (<1s) and deterministic.
 */
@Ignore("TODO(bytecode-only): uses fallback")
class ScriptSubsetJvmTest_Additions4 {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value
    private suspend fun evalList(code: String): List<Any?> = (Scope().eval(code) as ObjList).list.map { (it as? ObjInt)?.value ?: it }

    @Test
    fun mapsAndSetsBasics_jvm_only() = runBlocking {
        // Validate simple list map behavior without relying on extra stdlib
        val code = """
            val src = [1,2,2,3,1]
            // map over list
            val doubled = src.map { it + 1 }
            doubled.size()
        """.trimIndent()
        val r = evalInt(code)
        // doubled size == original size (5)
        assertEquals(5L, r)
    }

    @Test
    fun optionalChainingDeep_jvm_only() = runBlocking {
        val code = """
            class A() { fun b() { null } }
            val a = A()
            val r1 = a?.b()?.c
            val r2 = (a?.b()?.c ?: 7)
            r2
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(7L, r)
    }

    @Test
    fun whenExpressionBasics_jvm_only() = runBlocking {
        val code = """
            fun f(x) {
                when(x) {
                    0 -> 100
                    1 -> 200
                    else -> 300
                }
            }
            f(0) + f(1) + f(2)
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(600L, r)
    }

    @Test
    fun tryCatchFinallyWithReturn_jvm_only() = runBlocking {
        val code = """
            fun g(x) {
                var t = 0
                try {
                    if (x < 0) throw("oops")
                    t = x
                } catch (e) {
                    t = 5
                } finally {
                    t = t + 1
                }
                t
            }
            g(-1) + g(3)
        """.trimIndent()
        val r = evalInt(code)
        // g(-1): catch sets 5, finally +1 => 6; g(3): t=3, finally +1 => 4; total 10
        assertEquals(10L, r)
    }

    @Test
    fun pooling_edge_case_closure_and_exception_jvm_only() = runBlocking {
        val code = """
            fun maker(base) { { base + 1 } }
            val c = maker(41)
            var r = 0
            try {
                r = c()
                throw("fail")
            } catch (e) {
                r = r + 1
            }
            r
        """.trimIndent()
        // OFF
        PerfFlags.SCOPE_POOL = false
        val off = evalInt(code)
        // ON
        PerfFlags.SCOPE_POOL = true
        val on = evalInt(code)
        assertEquals(43L, off)
        assertEquals(43L, on)
        // reset
        PerfFlags.SCOPE_POOL = false
    }

    @Test
    fun forWhileNested_jvm_only() = runBlocking {
        val code = """
            var s = 0
            for (i in 1..10) {
                var j = 0
                while (j < 3) {
                    if (i % 2 == 0 && j == 1) { j = j + 1; continue }
                    s = s + i + j
                    j = j + 1
                }
            }
            s
        """.trimIndent()
        val r = evalInt(code)
        // Compute expected quickly in Kotlin mirror
        var expected = 0L
        for (i in 1..10) {
            var j = 0
            while (j < 3) {
                if (i % 2 == 0 && j == 1) { j += 1; continue }
                expected += i + j
                j += 1
            }
        }
        assertEquals(expected, r)
        assertTrue(expected > 0)
    }
}
