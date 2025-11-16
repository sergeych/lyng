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

class IndexPicABTest {

    private fun outFile(): File = File("lynglib/build/index_pic_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Index PIC A/B results\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    private suspend fun buildStringIndexScript(len: Int, iters: Int): Script {
        // Build a long string and index it by cycling positions
        val content = (0 until len).joinToString("") { i ->
            val ch = 'a' + (i % 26)
            ch.toString()
        }
        val src = """
            val s = "$content"
            var acc = 0
            for(i in 0..${iters - 1}) {
                val j = i % ${len}
                // Compare to a 1-char string to avoid needing Char.toInt(); still exercises indexing path
                if (s[j] == "a") { acc += 1 } else { acc += 0 }
            }
            acc
        """.trimIndent()
        return Compiler.compile(Source("<idx-string>", src), Script.defaultImportManager)
    }

    private suspend fun buildMapIndexScript(keys: Int, iters: Int): Script {
        // Build a map of ("kX" -> X) and repeatedly access by key cycling
        val entries = (0 until keys).joinToString(", ") { i -> "\"k$i\" => $i" }
        val src = """
            // Build via Map(entry1, entry2, ...), not a list literal
            val m = Map($entries)
            var acc = 0
            for(i in 0..${iters - 1}) {
                val k = "k" + (i % ${keys})
                acc += (m[k] ?: 0)
            }
            acc
        """.trimIndent()
        return Compiler.compile(Source("<idx-map>", src), Script.defaultImportManager)
    }

    private suspend fun runOnce(script: Script): Long {
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        if (result !is ObjInt) println("[DEBUG_LOG] result=${result?.javaClass?.simpleName}")
        return t
    }

    @Test
    fun ab_index_pic_and_size() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        val savedIndexPic = PerfFlags.INDEX_PIC
        val savedIndexSize4 = PerfFlags.INDEX_PIC_SIZE_4
        val savedCounters = PerfFlags.PIC_DEBUG_COUNTERS

        try {
            val iters = 300_000
            val sLen = 512
            val mapKeys = 256
            val sScript = buildStringIndexScript(sLen, iters)
            val mScript = buildMapIndexScript(mapKeys, iters)

            fun header(which: String) { appendLine(f, "[DEBUG_LOG] A/B on $which (iters=$iters)") }

            // Baseline OFF
            PerfFlags.PIC_DEBUG_COUNTERS = true
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = false
            PerfFlags.INDEX_PIC_SIZE_4 = false
            header("String[Int], INDEX_PIC=OFF")
            val tSOff = runOnce(sScript)
            header("Map[String], INDEX_PIC=OFF")
            val tMOff = runOnce(mScript)
            appendLine(f, "[DEBUG_LOG]  OFF counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // PIC ON, size 2
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = true
            PerfFlags.INDEX_PIC_SIZE_4 = false
            val tSOn2 = runOnce(sScript)
            val tMOn2 = runOnce(mScript)
            appendLine(f, "[DEBUG_LOG]  ON size=2 counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // PIC ON, size 4
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = true
            PerfFlags.INDEX_PIC_SIZE_4 = true
            val tSOn4 = runOnce(sScript)
            val tMOn4 = runOnce(mScript)
            appendLine(f, "[DEBUG_LOG]  ON size=4 counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // Report
            appendLine(f, "[DEBUG_LOG] String[Int]  OFF=${tSOff} ns, ON(2)=${tSOn2} ns, ON(4)=${tSOn4} ns")
            appendLine(f, "[DEBUG_LOG] Map[String]  OFF=${tMOff} ns, ON(2)=${tMOn2} ns, ON(4)=${tMOn4} ns")
        } finally {
            PerfFlags.INDEX_PIC = savedIndexPic
            PerfFlags.INDEX_PIC_SIZE_4 = savedIndexSize4
            PerfFlags.PIC_DEBUG_COUNTERS = savedCounters
        }
    }
}

private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
