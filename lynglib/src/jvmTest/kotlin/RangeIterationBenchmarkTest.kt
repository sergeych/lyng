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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjInt
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Baseline range iteration benchmark. It measures for-loops over integer ranges under
 * current implementation and records timings. When RANGE_FAST_ITER is implemented,
 * this test will also serve for OFF vs ON A/B.
 */
class RangeIterationBenchmarkTest {

    private fun outFile(): File = File("lynglib/build/range_iter_bench.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Range iteration benchmark results\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    private suspend fun buildSumScriptInclusive(n: Int, iters: Int): Script {
        // Sum 0..n repeatedly to stress iteration
        val src = """
            var total = 0
            for (k in 0..${iters - 1}) {
                var s = 0
                for (i in 0..$n) { s += i }
                total += s
            }
            total
        """.trimIndent()
        return Compiler.compile(Source("<range-inc>", src), Script.defaultImportManager)
    }

    private suspend fun buildSumScriptExclusive(n: Int, iters: Int): Script {
        val src = """
            var total = 0
            for (k in 0..${iters - 1}) {
                var s = 0
                for (i in 0..<$n) { s += i }
                total += s
            }
            total
        """.trimIndent()
        return Compiler.compile(Source("<range-exc>", src), Script.defaultImportManager)
    }

    private suspend fun buildSumScriptReversed(n: Int, iters: Int): Script {
        val src = """
            var total = 0
            for (k in 0..${iters - 1}) {
                var s = 0
                // reversed-like loop using countdown range (n..0)
                for (i in $n..0) { s += i }
                total += s
            }
            total
        """.trimIndent()
        return Compiler.compile(Source("<range-rev>", src), Script.defaultImportManager)
    }

    private suspend fun buildSumScriptNegative(n: Int, iters: Int): Script {
        // Sum -n..n repeatedly
        val src = """
            var total = 0
            for (k in 0..${iters - 1}) {
                var s = 0
                for (i in -$n..$n) { s += (i < 0 ? -i : i) }
                total += s
            }
            total
        """.trimIndent()
        return Compiler.compile(Source("<range-neg>", src), Script.defaultImportManager)
    }

    private suspend fun buildSumScriptEmpty(iters: Int): Script {
        // Empty range 1..0 should not iterate
        val src = """
            var total = 0
            for (k in 0..${iters - 1}) {
                var s = 0
                for (i in 1..0) { s += 1 }
                total += s
            }
            total
        """.trimIndent()
        return Compiler.compile(Source("<range-empty>", src), Script.defaultImportManager)
    }

    private suspend fun runOnce(script: Script): Long {
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        if (result !is ObjInt) println("[DEBUG_LOG] result=${result?.javaClass?.simpleName}")
        return t
    }

    @Test
    fun bench_range_iteration_baseline() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        val savedFlag = PerfFlags.RANGE_FAST_ITER
        try {
            val n = 1000
            val iters = 500

            // Baseline with current flag (OFF by default)
            PerfFlags.RANGE_FAST_ITER = false
            val sIncOff = buildSumScriptInclusive(n, iters)
            val tIncOff = runOnce(sIncOff)
            val sExcOff = buildSumScriptExclusive(n, iters)
            val tExcOff = runOnce(sExcOff)
            appendLine(f, "[DEBUG_LOG] OFF inclusive=${tIncOff} ns, exclusive=${tExcOff} ns")

            // Also record ON times
            PerfFlags.RANGE_FAST_ITER = true
            val sIncOn = buildSumScriptInclusive(n, iters)
            val tIncOn = runOnce(sIncOn)
            val sExcOn = buildSumScriptExclusive(n, iters)
            val tExcOn = runOnce(sExcOn)
            appendLine(f, "[DEBUG_LOG] ON  inclusive=${tIncOn} ns, exclusive=${tExcOn} ns")

            // Additional scenarios: reversed, negative, empty
            PerfFlags.RANGE_FAST_ITER = false
            val sRevOff = buildSumScriptReversed(n, iters)
            val tRevOff = runOnce(sRevOff)
            val sNegOff = buildSumScriptNegative(n, iters)
            val tNegOff = runOnce(sNegOff)
            val sEmptyOff = buildSumScriptEmpty(iters)
            val tEmptyOff = runOnce(sEmptyOff)
            appendLine(f, "[DEBUG_LOG] OFF reversed=${tRevOff} ns, negative=${tNegOff} ns, empty=${tEmptyOff} ns")

            PerfFlags.RANGE_FAST_ITER = true
            val sRevOn = buildSumScriptReversed(n, iters)
            val tRevOn = runOnce(sRevOn)
            val sNegOn = buildSumScriptNegative(n, iters)
            val tNegOn = runOnce(sNegOn)
            val sEmptyOn = buildSumScriptEmpty(iters)
            val tEmptyOn = runOnce(sEmptyOn)
            appendLine(f, "[DEBUG_LOG] ON  reversed=${tRevOn} ns, negative=${tNegOn} ns, empty=${tEmptyOn} ns")
        } finally {
            PerfFlags.RANGE_FAST_ITER = savedFlag
        }
    }
}

private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
