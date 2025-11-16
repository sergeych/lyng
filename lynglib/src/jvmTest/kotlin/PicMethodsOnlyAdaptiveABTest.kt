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
 * A/B micro-benchmark to compare methods-only adaptive PIC OFF vs ON.
 * Ensures fixed PIC sizes (2-entry) and only toggles PIC_ADAPTIVE_METHODS_ONLY.
 * Writes a summary to lynglib/build/pic_methods_only_adaptive_ab_results.txt
 */
class PicMethodsOnlyAdaptiveABTest {

    private fun outFile(): File = File("lynglib/build/pic_methods_only_adaptive_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] PIC Adaptive (methods-only) 2→4 A/B results\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    private suspend fun buildScriptForMethodShapes(shapes: Int, iters: Int): Script {
        // Define N classes C0..C{shapes-1} each with method f() { i }
        val classes = (0 until shapes).joinToString("\n") { i ->
            "class MC$i { fun f() { $i } }"
        }
        val inits = (0 until shapes).joinToString(", ") { i -> "MC$i()" }
        val body = buildString {
            append("var s = 0\n")
            append("val a = [${inits}]\n")
            append("for(i in 0..${iters - 1}) {\n")
            append("  val o = a[i % ${shapes}]\n")
            append("  s += o.f()\n")
            append("}\n")
            append("s\n")
        }
        val src = classes + "\n" + body
        return Compiler.compile(Source("<pic-meth-only-shapes>", src), Script.defaultImportManager)
    }

    private suspend fun runOnce(script: Script): Long {
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        if (result !is ObjInt) println("[DEBUG_LOG] result=${result?.javaClass?.simpleName}")
        return t
    }

    @Test
    fun ab_methods_only_adaptive_pic() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        // Save flags
        val savedAdaptive2To4 = PerfFlags.PIC_ADAPTIVE_2_TO_4
        val savedAdaptiveMethodsOnly = PerfFlags.PIC_ADAPTIVE_METHODS_ONLY
        val savedFieldPic = PerfFlags.FIELD_PIC
        val savedMethodPic = PerfFlags.METHOD_PIC
        val savedFieldSize4 = PerfFlags.FIELD_PIC_SIZE_4
        val savedMethodSize4 = PerfFlags.METHOD_PIC_SIZE_4
        val savedCounters = PerfFlags.PIC_DEBUG_COUNTERS

        try {
            // Fixed-size 2-entry PICs, enable PICs, disable global adaptivity
            PerfFlags.FIELD_PIC = true
            PerfFlags.METHOD_PIC = true
            PerfFlags.FIELD_PIC_SIZE_4 = false
            PerfFlags.METHOD_PIC_SIZE_4 = false
            PerfFlags.PIC_ADAPTIVE_2_TO_4 = false

            val iters = 200_000
            val meth3 = buildScriptForMethodShapes(3, iters)
            val meth4 = buildScriptForMethodShapes(4, iters)

            fun header(which: String) { appendLine(f, "[DEBUG_LOG] A/B Methods-only adaptive on $which (iters=$iters)") }

            // OFF pass
            PerfFlags.PIC_DEBUG_COUNTERS = true
            PerfStats.resetAll()
            PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = false
            header("methods-3")
            val tM3Off = runOnce(meth3)
            header("methods-4")
            val tM4Off = runOnce(meth4)
            appendLine(f, "[DEBUG_LOG]  OFF counters: methodHit=${PerfStats.methodPicHit} methodMiss=${PerfStats.methodPicMiss}")

            // ON pass
            PerfStats.resetAll()
            PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = true
            val tM3On = runOnce(meth3)
            val tM4On = runOnce(meth4)
            appendLine(f, "[DEBUG_LOG]  ON counters: methodHit=${PerfStats.methodPicHit} methodMiss=${PerfStats.methodPicMiss}")

            // Report
            appendLine(f, "[DEBUG_LOG] methods-3 OFF=${tM3Off} ns, ON=${tM3On} ns, delta=${tM3Off - tM3On} ns")
            appendLine(f, "[DEBUG_LOG] methods-4 OFF=${tM4Off} ns, ON=${tM4On} ns, delta=${tM4Off - tM4On} ns")
        } finally {
            // Restore
            PerfFlags.PIC_ADAPTIVE_2_TO_4 = savedAdaptive2To4
            PerfFlags.PIC_ADAPTIVE_METHODS_ONLY = savedAdaptiveMethodsOnly
            PerfFlags.FIELD_PIC = savedFieldPic
            PerfFlags.METHOD_PIC = savedMethodPic
            PerfFlags.FIELD_PIC_SIZE_4 = savedFieldSize4
            PerfFlags.METHOD_PIC_SIZE_4 = savedMethodSize4
            PerfFlags.PIC_DEBUG_COUNTERS = savedCounters
        }
    }
}

private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
