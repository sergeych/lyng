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

class PicAdaptiveABTest {

    private fun outFile(): File = File("lynglib/build/pic_adaptive_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] PIC Adaptive 2→4 A/B results\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    private suspend fun buildScriptForMethodShapes(shapes: Int, iters: Int): Script {
        // Define N classes C0..C{shapes-1} each with method f() { 1 }
        val classes = (0 until shapes).joinToString("\n") { i ->
            "class C$i { fun f() { $i } var x = 0 }"
        }
        val inits = (0 until shapes).joinToString(", ") { i -> "C$i()" }
        val calls = buildString {
            append("var s = 0\n")
            append("val a = [${inits}]\n")
            append("for(i in 0..${iters - 1}) {\n")
            append("  val o = a[i % ${shapes}]\n")
            append("  s += o.f()\n")
            append("}\n")
            append("s\n")
        }
        val src = classes + "\n" + calls
        return Compiler.compile(Source("<pic-method-shapes>", src), Script.defaultImportManager)
    }

    private suspend fun buildScriptForFieldShapes(shapes: Int, iters: Int): Script {
        // Each class has a mutable field x initialized to 0; read and write it
        val classes = (0 until shapes).joinToString("\n") { i ->
            "class F$i { var x = 0 }"
        }
        val inits = (0 until shapes).joinToString(", ") { i -> "F$i()" }
        val body = buildString {
            append("var s = 0\n")
            append("val a = [${inits}]\n")
            append("for(i in 0..${iters - 1}) {\n")
            append("  val o = a[i % ${shapes}]\n")
            append("  s += o.x\n")
            append("  o.x = o.x + 1\n")
            append("}\n")
            append("s\n")
        }
        val src = classes + "\n" + body
        return Compiler.compile(Source("<pic-field-shapes>", src), Script.defaultImportManager)
    }

    private suspend fun runOnce(script: Script): Long {
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        if (result !is ObjInt) println("[DEBUG_LOG] result=${result?.javaClass?.simpleName}")
        return t
    }

    @Test
    fun ab_adaptive_pic() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        val savedAdaptive = PerfFlags.PIC_ADAPTIVE_2_TO_4
        val savedCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val savedFieldPic = PerfFlags.FIELD_PIC
        val savedMethodPic = PerfFlags.METHOD_PIC
        val savedFieldPicSize4 = PerfFlags.FIELD_PIC_SIZE_4
        val savedMethodPicSize4 = PerfFlags.METHOD_PIC_SIZE_4

        try {
            // Ensure baseline PICs are enabled and fixed-size flags OFF to isolate adaptivity
            PerfFlags.FIELD_PIC = true
            PerfFlags.METHOD_PIC = true
            PerfFlags.FIELD_PIC_SIZE_4 = false
            PerfFlags.METHOD_PIC_SIZE_4 = false

            // Prepare workloads with 3 and 4 receiver shapes
            val iters = 200_000
            val meth3 = buildScriptForMethodShapes(3, iters)
            val meth4 = buildScriptForMethodShapes(4, iters)
            val fld3 = buildScriptForFieldShapes(3, iters)
            val fld4 = buildScriptForFieldShapes(4, iters)

            fun header(which: String) {
                appendLine(f, "[DEBUG_LOG] A/B Adaptive PIC on $which (iters=$iters)")
            }

            // OFF pass
            PerfFlags.PIC_DEBUG_COUNTERS = true
            PerfStats.resetAll()
            PerfFlags.PIC_ADAPTIVE_2_TO_4 = false
            header("methods-3")
            val tM3Off = runOnce(meth3)
            header("methods-4")
            val tM4Off = runOnce(meth4)
            header("fields-3")
            val tF3Off = runOnce(fld3)
            header("fields-4")
            val tF4Off = runOnce(fld4)
            appendLine(f, "[DEBUG_LOG]  OFF counters: methodHit=${PerfStats.methodPicHit} methodMiss=${PerfStats.methodPicMiss} fieldHit=${PerfStats.fieldPicHit} fieldMiss=${PerfStats.fieldPicMiss}")

            // ON pass
            PerfStats.resetAll()
            PerfFlags.PIC_ADAPTIVE_2_TO_4 = true
            val tM3On = runOnce(meth3)
            val tM4On = runOnce(meth4)
            val tF3On = runOnce(fld3)
            val tF4On = runOnce(fld4)
            appendLine(f, "[DEBUG_LOG]  ON counters: methodHit=${PerfStats.methodPicHit} methodMiss=${PerfStats.methodPicMiss} fieldHit=${PerfStats.fieldPicHit} fieldMiss=${PerfStats.fieldPicMiss}")

            // Report
            appendLine(f, "[DEBUG_LOG] methods-3 OFF=${tM3Off} ns, ON=${tM3On} ns, delta=${tM3Off - tM3On} ns")
            appendLine(f, "[DEBUG_LOG] methods-4 OFF=${tM4Off} ns, ON=${tM4On} ns, delta=${tM4Off - tM4On} ns")
            appendLine(f, "[DEBUG_LOG] fields-3  OFF=${tF3Off} ns, ON=${tF3On} ns, delta=${tF3Off - tF3On} ns")
            appendLine(f, "[DEBUG_LOG] fields-4  OFF=${tF4Off} ns, ON=${tF4On} ns, delta=${tF4Off - tF4On} ns")
        } finally {
            PerfFlags.PIC_ADAPTIVE_2_TO_4 = savedAdaptive
            PerfFlags.PIC_DEBUG_COUNTERS = savedCounters
            PerfFlags.FIELD_PIC = savedFieldPic
            PerfFlags.METHOD_PIC = savedMethodPic
            PerfFlags.FIELD_PIC_SIZE_4 = savedFieldPicSize4
            PerfFlags.METHOD_PIC_SIZE_4 = savedMethodPicSize4
        }
    }
}

private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
