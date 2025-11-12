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
 * JVM micro-benchmarks for FieldRef and MethodCallRef PICs.
 */

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals

class PicBenchmarkTest {
    @Test
    fun benchmarkFieldGetSetPic() = runBlocking {
        val iterations = 300_000
        val script = """
            class C() {
                var x = 0
                fun add1() { x = x + 1 }
                fun getX() { x }
            }
            val c = C()
            var i = 0
            while(i < $iterations) {
                c.x = c.x + 1
                i = i + 1
            }
            c.x
        """.trimIndent()

        // PIC OFF
        PerfFlags.FIELD_PIC = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] Field PIC=OFF: ${(t1 - t0) / 1_000_000.0} ms")
        assertEquals(iterations.toLong(), r1)

        // PIC ON
        PerfFlags.FIELD_PIC = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] Field PIC=ON: ${(t3 - t2) / 1_000_000.0} ms")
        assertEquals(iterations.toLong(), r2)
        if (PerfFlags.PIC_DEBUG_COUNTERS) {
            println("[DEBUG_LOG] [PIC] field get hit=${net.sergeych.lyng.PerfStats.fieldPicHit} miss=${net.sergeych.lyng.PerfStats.fieldPicMiss}")
            println("[DEBUG_LOG] [PIC] field set hit=${net.sergeych.lyng.PerfStats.fieldPicSetHit} miss=${net.sergeych.lyng.PerfStats.fieldPicSetMiss}")
        }
    }

    @Test
    fun benchmarkMethodPic() = runBlocking {
        val iterations = 200_000
        val script = """
            class C() {
                var x = 0
                fun add(v) { x = x + v }
                fun get() { x }
            }
            val c = C()
            var i = 0
            while(i < $iterations) {
                c.add(1)
                i = i + 1
            }
            c.get()
        """.trimIndent()

        // PIC OFF
        PerfFlags.METHOD_PIC = false
        val scope1 = Scope()
        val t0 = System.nanoTime()
        val r1 = (scope1.eval(script) as ObjInt).value
        val t1 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] Method PIC=OFF: ${(t1 - t0) / 1_000_000.0} ms")
        assertEquals(iterations.toLong(), r1)

        // PIC ON
        PerfFlags.METHOD_PIC = true
        val scope2 = Scope()
        val t2 = System.nanoTime()
        val r2 = (scope2.eval(script) as ObjInt).value
        val t3 = System.nanoTime()
        println("[DEBUG_LOG] [BENCH] Method PIC=ON: ${(t3 - t2) / 1_000_000.0} ms")
        assertEquals(iterations.toLong(), r2)
    }
}
