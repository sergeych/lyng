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
 * A/B micro-benchmark for index WRITE paths (Map[String] put, List[Int] set).
 * Measures OFF vs ON for INDEX_PIC and then size 2 vs 4 (INDEX_PIC_SIZE_4).
 * Produces [DEBUG_LOG] output in lynglib/build/index_write_ab_results.txt
 */
class IndexWritePathABTest {

    private fun outFile(): File = File("lynglib/build/index_write_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Index WRITE PIC A/B results\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    private suspend fun buildMapWriteScript(keys: Int, iters: Int): Script {
        // Construct map with keys k0..k{keys-1} and then perform writes in a tight loop
        val initEntries = (0 until keys).joinToString(", ") { i -> "\"k$i\" => $i" }
        val src = """
            var acc = 0
            val m = Map($initEntries)
            for(i in 0..${iters - 1}) {
                val k = "k" + (i % $keys)
                m[k] = i
                acc += (m[k] ?: 0)
            }
            acc
        """.trimIndent()
        return Compiler.compile(Source("<idx-map-write>", src), Script.defaultImportManager)
    }

    private suspend fun buildListWriteScript(len: Int, iters: Int): Script {
        val initList = (0 until len).joinToString(", ") { i -> i.toString() }
        val src = """
            var acc = 0
            val a = [$initList]
            for(i in 0..${iters - 1}) {
                val j = i % $len
                a[j] = i
                acc += a[j]
            }
            acc
        """.trimIndent()
        return Compiler.compile(Source("<idx-list-write>", src), Script.defaultImportManager)
    }

    private suspend fun runOnce(script: Script): Long {
        val scope = Script.newScope()
        var result: Obj? = null
        val t = measureNanoTime { result = script.execute(scope) }
        if (result !is ObjInt) println("[DEBUG_LOG] result=${result?.javaClass?.simpleName}")
        return t
    }

    @Test
    fun ab_index_write_paths() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        val savedIndexPic = PerfFlags.INDEX_PIC
        val savedIndexSize4 = PerfFlags.INDEX_PIC_SIZE_4
        val savedCounters = PerfFlags.PIC_DEBUG_COUNTERS

        try {
            val iters = 250_000
            val mapKeys = 256
            val listLen = 1024
            val mScript = buildMapWriteScript(mapKeys, iters)
            val lScript = buildListWriteScript(listLen, iters)

            fun header(which: String) { appendLine(f, "[DEBUG_LOG] A/B on $which (iters=$iters)") }

            // Baseline OFF
            PerfFlags.PIC_DEBUG_COUNTERS = true
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = false
            PerfFlags.INDEX_PIC_SIZE_4 = false
            header("Map[String] write, INDEX_PIC=OFF")
            val tMOff = runOnce(mScript)
            header("List[Int] write, INDEX_PIC=OFF")
            val tLOff = runOnce(lScript)
            appendLine(f, "[DEBUG_LOG]  OFF counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // PIC ON, size 2
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = true
            PerfFlags.INDEX_PIC_SIZE_4 = false
            val tMOn2 = runOnce(mScript)
            val tLOn2 = runOnce(lScript)
            appendLine(f, "[DEBUG_LOG]  ON size=2 counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // PIC ON, size 4
            PerfStats.resetAll()
            PerfFlags.INDEX_PIC = true
            PerfFlags.INDEX_PIC_SIZE_4 = true
            val tMOn4 = runOnce(mScript)
            val tLOn4 = runOnce(lScript)
            appendLine(f, "[DEBUG_LOG]  ON size=4 counters: indexHit=${PerfStats.indexPicHit} indexMiss=${PerfStats.indexPicMiss}")

            // Report
            appendLine(f, "[DEBUG_LOG] Map[String] WRITE  OFF=$tMOff ns, ON(2)=$tMOn2 ns, ON(4)=$tMOn4 ns")
            appendLine(f, "[DEBUG_LOG] List[Int] WRITE  OFF=$tLOff ns, ON(2)=$tLOn2 ns, ON(4)=$tLOn4 ns")
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
