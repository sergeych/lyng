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
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjList
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Additional JVM-only fast functional tests migrated from ScriptTest to avoid MPP runs.
 * Keep each test fast (<1s) and with clear assertions.
 */
@Ignore("TODO(bytecode-only): uses fallback (logical ops/binarySearch)")
class ScriptSubsetJvmTest_Additions {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value
    private suspend fun evalList(code: String): List<Any?> = (Scope().eval(code) as ObjList).list.map { (it as? ObjInt)?.value ?: it }

    @Test
    fun rangesAndForLoop_jvm_only() = runBlocking {
        val code = """
            var s = 0
            for (i in 1..100) s = s + i
            s
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(5050L, r)
    }

    @Test
    fun classFieldsAndMethods_jvm_only() = runBlocking {
        val n = 20000
        val code = """
            class Counter() {
                var x = 0
                fun inc() { x = x + 1 }
                fun get() { x }
            }
            val c = Counter()
            var i = 0
            while (i < $n) { c.inc(); i = i + 1 }
            c.get()
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(n.toLong(), r)
    }

    @Test
    fun elvisAndLogicalChains_jvm_only() = runBlocking {
        val n = 10000
        val code = """
            val maybe = null
            var s = 0
            var i = 0
            while (i < $n) {
                s = s + (maybe ?: 0)
                if ((i % 3 == 0 && true) || false) { s = s + 1 } else { s = s + 2 }
                s = s + (i - (i / 2) * 2)
                i = i + 1
            }
            s
        """.trimIndent()
        val r = evalInt(code)
        // Kotlin mirror for correctness
        var s = 0L
        var i = 0
        while (i < n) {
            if ((i % 3 == 0 && true) || false) s += 1 else s += 2
            s += (i - (i / 2) * 2)
            i += 1
        }
        assertEquals(s, r)
    }

    @Test
    fun sortedInsertWithBinarySearch_jvm_only() = runBlocking {
        val code = """
            val src = [3,1,2]
            val result = []
            for (x in src) {
                val i = result.binarySearch(x)
                result.insertAt(if (i < 0) -i-1 else i, x)
            }
            result
        """.trimIndent()
        val r = evalList(code)
        assertEquals(listOf(1L, 2L, 3L), r)
    }
}


@Ignore("TODO(bytecode-only): uses fallback")
class ScriptSubsetJvmTest_Additions2 {
    private suspend fun evalInt(code: String): Long = (Scope().eval(code) as ObjInt).value

    @Test
    fun optionalMethodCallWithElvis_jvm_only() = runBlocking {
        val code = """
            class C() { fun get() { 5 } }
            val a = null
            (a?.get() ?: 7)
        """.trimIndent()
        val r = evalInt(code)
        assertEquals(7L, r)
    }

    @Test
    fun continueAndBreakInWhile_jvm_only() = runBlocking {
        val code = """
            var s = 0
            var i = 0
            while (i <= 100) {
                if (i > 50) break
                if (i % 2 == 1) { i = i + 1; continue }
                s = s + i
                i = i + 1
            }
            s
        """.trimIndent()
        val r = evalInt(code)
        // Sum of even numbers from 0 to 50 inclusive: 2 * (0+1+...+25) = 2 * 325 = 650
        assertEquals(650L, r)
    }
}
