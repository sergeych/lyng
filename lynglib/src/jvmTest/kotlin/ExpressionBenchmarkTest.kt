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

/*
 * JVM micro-benchmark for expression evaluation with RVAL_FASTPATH.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionBenchmarkTest {
    @Test
    fun benchmarkExpressionChains() = runBlocking {
        val n = 350_000
        val script = """
            // arithmetic + elvis + logical chains
            val maybe = null
            var s = 0
            var i = 0
            while (i < $n) {
                // exercise elvis on a null
                s = s + (maybe ?: 0)
                // branch using booleans without coercion to int
                if ((i % 3 == 0 && true) || false) { s = s + 1 } else { s = s + 2 }
                // parity via arithmetic only (avoid adding booleans)
                s = s + (i - (i / 2) * 2)
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] expr-chain x$n [RVAL_FASTPATH=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.RVAL_FASTPATH = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] expr-chain x$n [RVAL_FASTPATH=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // correctness: compute expected with simple kotlin logic mirroring the loop
        var s = 0L
        var i = 0
        while (i < n) {
            if ((i % 3 == 0 && true) || false) s += 1 else s += 2
            // parity via arithmetic only, matches script's single parity addition
            s += i - (i / 2) * 2
            i += 1
        }
        assertEquals(s, r1)
        assertEquals(s, r2)
    }

    @Test
    fun benchmarkListIndexReads() = runBlocking {
        val n = 350_000
        val script = """
            val list = (1..10).toList()
            var s = 0
            var i = 0
            while (i < $n) {
                // exercise fast index path on ObjList + ObjInt index
                s = s + list[3]
                s = s + list[7]
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] list-index x$n [RVAL_FASTPATH=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.RVAL_FASTPATH = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] list-index x$n [RVAL_FASTPATH=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // correctness: list = [1..10]; each loop adds list[3]+list[7] = 4 + 8 = 12
        val expected = 12L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }

    @Test
    fun benchmarkFieldReadPureReceiver() = runBlocking {
        val n = 300_000
        val script = """
            class C(){ var x = 1; var y = 2 }
            val c = C()
            var s = 0
            var i = 0
            while (i < $n) {
                // repeated reads on the same monomorphic receiver
                s = s + c.x
                s = s + c.y
                i = i + 1
            }
            s
        """.trimIndent()

        // OFF
        PerfFlags.RVAL_FASTPATH = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] field-read x$n [RVAL_FASTPATH=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // ON
        PerfFlags.RVAL_FASTPATH = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] field-read x$n [RVAL_FASTPATH=ON]: ${(t3 - t2)/1_000_000.0} ms")

        val expected = (1L + 2L) * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
