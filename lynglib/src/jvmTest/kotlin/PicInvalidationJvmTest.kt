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

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.PerfFlags
import net.sergeych.lyng.PerfStats
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PicInvalidationJvmTest {
    @Test
    fun fieldPicInvalidatesOnClassLayoutChange() = runBlocking {
        // Enable counters and PICs
        PerfFlags.FIELD_PIC = true
        PerfFlags.PIC_DEBUG_COUNTERS = true
        PerfStats.resetAll()

        val scope = Scope()
        // Declare a class and warm up field access
        val script = """
            class C() {
                var x = 0
                fun getX() { x }
            }
            val c = C()
            var i = 0
            while (i < 1000) {
                // warm read path
                val t = c.x
                i = i + 1
            }
            c.getX()
        """.trimIndent()
        val r1 = (scope.eval(script) as ObjInt).value
        assertEquals(0L, r1)
        val hitsBefore = PerfStats.fieldPicHit
        val missesBefore = PerfStats.fieldPicMiss
        assertTrue(hitsBefore >= 1, "Expected some PIC hits after warm-up")

        // Mutate class layout from Kotlin side to bump layoutVersion and invalidate PIC
        val cls = (scope["C"]!!.value as ObjClass)
        cls.createClassField("yy", ObjInt(1), isMutable = false)

        // Access the same field again; first access after version bump should miss PIC
        val r2 = (scope.eval("c.x") as ObjInt).value
        assertEquals(0L, r2)
        val missesAfter = PerfStats.fieldPicMiss
        assertTrue(missesAfter >= missesBefore + 1, "Expected PIC miss after class layout change")

        // Optional summary when counters enabled
        if (PerfFlags.PIC_DEBUG_COUNTERS) {
            println("[DEBUG_LOG] [PIC] field get hit=${PerfStats.fieldPicHit} miss=${PerfStats.fieldPicMiss}")
            println("[DEBUG_LOG] [PIC] field set hit=${PerfStats.fieldPicSetHit} miss=${PerfStats.fieldPicSetMiss}")
        }

        // Disable counters to avoid affecting other tests
        PerfFlags.PIC_DEBUG_COUNTERS = false
    }

    @Test
    fun methodPicInvalidatesOnClassLayoutChange() = runBlocking {
        PerfFlags.METHOD_PIC = true
        PerfFlags.PIC_DEBUG_COUNTERS = true
        PerfStats.resetAll()

        val scope = Scope()
        val script = """
            class D() {
                var x = 0
                fun inc() { x = x + 1 }
                fun get() { x }
            }
            val d = D()
            var i = 0
            while (i < 1000) {
                d.inc()
                i = i + 1
            }
            d.get()
        """.trimIndent()
        val r1 = (scope.eval(script) as ObjInt).value
        assertEquals(1000L, r1)
        val mhBefore = PerfStats.methodPicHit
        val mmBefore = PerfStats.methodPicMiss
        assertTrue(mhBefore >= 1, "Expected method PIC hits after warm-up")

        // Bump layout by adding a new class field
        val cls = (scope["D"]!!.value as ObjClass)
        cls.createClassField("zz", ObjInt(0), isMutable = false)

        // Next invocation should miss and then re-fill
        val r2 = (scope.eval("d.get()") as ObjInt).value
        assertEquals(1000L, r2)
        val mmAfter = PerfStats.methodPicMiss
        assertTrue(mmAfter >= mmBefore + 1, "Expected method PIC miss after class layout change")

        // Optional summary when counters enabled
        if (PerfFlags.PIC_DEBUG_COUNTERS) {
            println("[DEBUG_LOG] [PIC] method hit=${PerfStats.methodPicHit} miss=${PerfStats.methodPicMiss}")
        }

        PerfFlags.PIC_DEBUG_COUNTERS = false
    }
}
