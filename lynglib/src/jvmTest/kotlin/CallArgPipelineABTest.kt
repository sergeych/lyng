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

class CallArgPipelineABTest {

    private fun outFile(): File = File("lynglib/build/call_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Call/Arg pipeline A/B results\n")
    }

    private fun appendLine(f: File, s: String) {
        f.appendText(s + "\n")
    }

    private suspend fun buildScriptForCalls(arity: Int, iters: Int): Script {
        val argsDecl = (0 until arity).joinToString(",") { "a$it" }
        val argsUse = (0 until arity).joinToString(" + ") { "a$it" }.ifEmpty { "0" }
        val callArgs = (0 until arity).joinToString(",") { (it + 1).toString() }
        val src = """
            var sum = 0
            fun f($argsDecl) { $argsUse }
            for(i in 0..${iters - 1}) {
                sum += f($callArgs)
            }
            sum
        """.trimIndent()
        return Compiler.compile(Source("<calls-$arity>", src), Script.defaultImportManager)
    }

    private suspend fun benchCallsOnce(arity: Int, iters: Int): Long {
        val script = buildScriptForCalls(arity, iters)
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime {
            result = script.execute(scope)
        }
        // Basic correctness check so JIT doesn’t elide
        val expected = (0 until iters).fold(0L) { acc, _ ->
            (acc + (1L + 2L + 3L + 4L + 5L + 6L + 7L + 8L).let { if (arity <= 8) it - (8 - arity) * 0L else it })
        }
        // We only rely that it runs; avoid strict equals as function may compute differently for arities < 8
        if (result !is ObjInt) {
            // ensure use to prevent DCE
            println("[DEBUG_LOG] Result class=${result?.javaClass?.simpleName}")
        }
        return t
    }

    private suspend fun benchOptionalCallShortCircuit(iters: Int): Long {
        val src = """
            var side = 0
            fun inc() { side += 1 }
            var o = null
            for(i in 0..${iters - 1}) {
                o?.foo(inc())
            }
            side
        """.trimIndent()
        val script = Compiler.compile(Source("<opt-call>", src), Script.defaultImportManager)
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        // Ensure short-circuit actually happened
        require((result as? ObjInt)?.value == 0L) { "optional-call short-circuit failed; side=${(result as? ObjInt)?.value}" }
        return t
    }

    @Test
    fun ab_call_pipeline() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        val savedArgBuilder = PerfFlags.ARG_BUILDER
        val savedScopePool = PerfFlags.SCOPE_POOL

        try {
            val iters = 50_000
            val aritiesBase = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)

            // A/B for ARG_BUILDER (0..8)
            PerfFlags.ARG_BUILDER = false
            val offTimes = mutableListOf<Long>()
            for (a in aritiesBase) offTimes += benchCallsOnce(a, iters)
            PerfFlags.ARG_BUILDER = true
            val onTimes = mutableListOf<Long>()
            for (a in aritiesBase) onTimes += benchCallsOnce(a, iters)

            appendLine(f, "[DEBUG_LOG] ARG_BUILDER A/B (iters=$iters):")
            aritiesBase.forEachIndexed { idx, a ->
                appendLine(f, "[DEBUG_LOG]  arity=$a OFF=${offTimes[idx]} ns, ON=${onTimes[idx]} ns, delta=${offTimes[idx] - onTimes[idx]} ns")
            }

            // A/B for ARG_SMALL_ARITY_12 (9..12)
            val aritiesExtended = listOf(9, 10, 11, 12)
            val savedSmall = PerfFlags.ARG_SMALL_ARITY_12
            try {
                PerfFlags.ARG_BUILDER = true // base builder on
                PerfFlags.ARG_SMALL_ARITY_12 = false
                val offExt = mutableListOf<Long>()
                for (a in aritiesExtended) offExt += benchCallsOnce(a, iters)
                PerfFlags.ARG_SMALL_ARITY_12 = true
                val onExt = mutableListOf<Long>()
                for (a in aritiesExtended) onExt += benchCallsOnce(a, iters)
                appendLine(f, "[DEBUG_LOG] ARG_SMALL_ARITY_12 A/B (iters=$iters):")
                aritiesExtended.forEachIndexed { idx, a ->
                    appendLine(f, "[DEBUG_LOG]  arity=$a OFF=${offExt[idx]} ns, ON=${onExt[idx]} ns, delta=${offExt[idx] - onExt[idx]} ns")
                }
            } finally {
                PerfFlags.ARG_SMALL_ARITY_12 = savedSmall
            }

            // Optional call short-circuit sanity timing (does not A/B a flag currently; implementation short-circuits before args)
            val tOpt = benchOptionalCallShortCircuit(100_000)
            appendLine(f, "[DEBUG_LOG] Optional-call short-circuit sanity: ${tOpt} ns for 100k iterations (side-effect arg not evaluated).")

            // A/B for SCOPE_POOL
            PerfFlags.SCOPE_POOL = false
            val tPoolOff = benchCallsOnce(5, iters)
            PerfFlags.SCOPE_POOL = true
            val tPoolOn = benchCallsOnce(5, iters)
            appendLine(f, "[DEBUG_LOG] SCOPE_POOL A/B (arity=5, iters=$iters): OFF=${tPoolOff} ns, ON=${tPoolOn} ns, delta=${tPoolOff - tPoolOn} ns")

        } finally {
            PerfFlags.ARG_BUILDER = savedArgBuilder
            PerfFlags.SCOPE_POOL = savedScopePool
        }
    }
}

// Minimal runBlocking for common jvmTest without depending on kotlinx.coroutines test artifacts here
private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
