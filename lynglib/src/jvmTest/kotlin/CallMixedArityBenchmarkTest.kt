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
 * JVM micro-benchmark for mixed-arity function calls and ARG_BUILDER.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private fun appendBenchLog(name: String, variant: String, ms: Double) {
    val f = File("lynglib/build/benchlogs/log.csv")
    f.parentFile.mkdirs()
    f.appendText("$name,$variant,$ms\n")
}

class CallMixedArityBenchmarkTest {
    @Test
    fun benchmarkMixedArityCalls() = runBlocking {
        val n = 200_000
        val script = """
            fun f0() { 1 }
            fun f1(a) { a }
            fun f2(a,b) { a + b }
            fun f3(a,b,c) { a + b + c }
            fun f4(a,b,c,d) { a + b + c + d }

            var s = 0
            var i = 0
            while (i < $n) {
                s = s + f0()
                s = s + f1(1)
                s = s + f2(1, 1)
                s = s + f3(1, 1, 1)
                s = s + f4(1, 1, 1, 1)
                i = i + 1
            }
            s
        """.trimIndent()

        // Baseline
        PerfFlags.ARG_BUILDER = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] mixed-arity x$n [ARG_BUILDER=OFF]: ${(t1 - t0)/1_000_000.0} ms")

        // Optimized
        PerfFlags.ARG_BUILDER = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] mixed-arity x$n [ARG_BUILDER=ON]: ${(t3 - t2)/1_000_000.0} ms")

        // Each loop: 1 + 1 + 2 + 3 + 4 = 11
        val expected = 11L * n
        assertEquals(expected, r1)
        assertEquals(expected, r2)
    }
}
