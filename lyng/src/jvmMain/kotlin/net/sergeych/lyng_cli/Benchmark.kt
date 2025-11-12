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

package net.sergeych.lyng_cli

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script

object BenchmarkRunner {
    private fun format(nanos: Long, iters: Int): String {
        val secs = nanos / 1_000_000_000.0
        val ips = iters / secs
        return "%.3f s, %.0f ops/s".format(secs, ips)
    }

    private suspend fun runCase(name: String, code: String, iters: Int): Pair<String, String> {
        val script = Compiler.compile(code)
        // warmup
        repeat(2) { script.execute(Script.newScope()) }
        val start = System.nanoTime()
        repeat(iters) { script.execute(Script.newScope()) }
        val end = System.nanoTime()
        return name to format(end - start, iters)
    }

    fun runAll() = runBlocking {
        val iterations = 2000
        val cases = listOf(
            // Field get/set in a loop
            "field_inc" to """
                class C { var x = 0 }
                var c = C()
                var i = 0
                while( i < 1000 ) { c.x = c.x + 1; i = i + 1 }
                c.x
            """.trimIndent(),
            // Pure arithmetic with literals
            "arith_literals" to """
                var s = 0
                var i = 0
                while( i < 1000 ) { s = s + 1 + 2 + 3 + 4 + 5; i = i + 1 }
                s
            """.trimIndent(),
            // Method call overhead via instance method
            "method_call" to """
                class C { fun inc() { this.x = this.x + 1 } var x = 0 }
                var c = C()
                var i = 0
                while( i < 1000 ) { c.inc(); i = i + 1 }
                c.x
            """.trimIndent()
        )

        println("[BENCHMARK] iterations per case: $iterations")
        for ((name, code) in cases) {
            val (n, res) = runCase(name, code, iterations)
            println("[BENCHMARK] $n: $res")
        }
    }
}
