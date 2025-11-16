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
 * A/B micro-benchmark to compare PerfFlags.PRIMITIVE_FASTOPS OFF vs ON.
 * JVM-only quick check using simple arithmetic/logic loops.
 */
package net.sergeych.lyng

import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test

class PrimitiveFastOpsABTest {

    private fun outFile(): File = File("lynglib/build/primitive_ab_results.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Primitive FastOps A/B results\n")
    }

    private fun appendLine(f: File, s: String) {
        f.appendText(s + "\n")
    }

    private fun benchIntArithmeticIters(iters: Int): Long {
        var acc = 0L
        val t = measureNanoTime {
            var a = 1L
            var b = 2L
            var c = 3L
            repeat(iters) {
                // mimic mix of +, -, *, /, %, shifts and comparisons
                a = (a + b) xor c
                b = (b * 3L + a) and 0x7FFF_FFFFL
                if ((b and 1L) == 0L) c = c + 1L else c = c - 1L
                acc = acc + (a and b) + (c or a)
            }
        }
        // use acc to prevent DCE
        if (acc == 42L) println("[DEBUG_LOG] impossible")
        return t
    }

    private fun benchBoolLogicIters(iters: Int): Long {
        var acc = 0
        val t = measureNanoTime {
            var a = true
            var b = false
            repeat(iters) {
                a = a || b
                b = !b && a
                if (a == b) acc++ else acc--
            }
        }
        if (acc == Int.MIN_VALUE) println("[DEBUG_LOG] impossible2")
        return t
    }

    @Test
    fun ab_compare_primitive_fastops() {
        // Save current settings
        val savedFast = PerfFlags.PRIMITIVE_FASTOPS
        val savedCounters = PerfFlags.PIC_DEBUG_COUNTERS
        val f = outFile()
        writeHeader(f)

        try {
            val iters = 500_000

            // OFF pass
            PerfFlags.PIC_DEBUG_COUNTERS = true
            PerfStats.resetAll()
            PerfFlags.PRIMITIVE_FASTOPS = false
            val tArithOff = benchIntArithmeticIters(iters)
            val tLogicOff = benchBoolLogicIters(iters)

            // ON pass
            PerfStats.resetAll()
            PerfFlags.PRIMITIVE_FASTOPS = true
            val tArithOn = benchIntArithmeticIters(iters)
            val tLogicOn = benchBoolLogicIters(iters)

            println("[DEBUG_LOG] A/B PrimitiveFastOps (iters=$iters):")
            println("[DEBUG_LOG]  Arithmetic OFF: ${'$'}tArithOff ns, ON: ${'$'}tArithOn ns, delta: ${'$'}{tArithOff - tArithOn} ns")
            println("[DEBUG_LOG]  Bool logic OFF: ${'$'}tLogicOff ns, ON: ${'$'}tLogicOn ns, delta: ${'$'}{tLogicOff - tLogicOn} ns")

            appendLine(f, "[DEBUG_LOG] A/B PrimitiveFastOps (iters=$iters):")
            appendLine(f, "[DEBUG_LOG]  Arithmetic OFF: ${'$'}tArithOff ns, ON: ${'$'}tArithOn ns, delta: ${'$'}{tArithOff - tArithOn} ns")
            appendLine(f, "[DEBUG_LOG]  Bool logic OFF: ${'$'}tLogicOff ns, ON: ${'$'}tLogicOn ns, delta: ${'$'}{tLogicOff - tLogicOn} ns")
        } finally {
            // restore
            PerfFlags.PRIMITIVE_FASTOPS = savedFast
            PerfFlags.PIC_DEBUG_COUNTERS = savedCounters
        }
    }
}
