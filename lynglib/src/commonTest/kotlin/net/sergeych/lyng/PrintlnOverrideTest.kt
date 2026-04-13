/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.bridge.globalBinder
import net.sergeych.lyng.obj.ObjVoid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrintlnOverrideTest {

    @Test
    fun testPrintlnOverrideWithAddFn() = runTest {
        val scope = Script.newScope()
        val output = mutableListOf<String>()

        // Override println with addFn
        scope.addFn("println") {
            val list = mutableListOf<String>()
            for (a in args.list) {
                list.add(toStringOf(a).value)
            }
            output.add(list.joinToString(" "))
            ObjVoid
        }

        scope.createChildScope().eval("""
            println("top level")
            fun nested() {
                println("inside function")
                fun deep_nested() {
                    println("deeply nested")
                }
                deep_nested()
            }
            nested()
            if (true) {
                println("inside block")
            }
        """.trimIndent())

        assertEquals(listOf("top level", "inside function", "deeply nested", "inside block"), output)
    }

    @Test
    fun testPrintlnOverrideWithGlobalBinder() = runTest {
        val scope = Script.newScope()
        val output = mutableListOf<String>()

        // Override println with globalBinder
        scope.globalBinder().bindGlobalFun("println") {
            val sb = StringBuilder()
            for (i in 0 until args.size) {
                if (i > 0) sb.append(" ")
                sb.append(string(i))
            }
            output.add(sb.toString())
            ObjVoid
        }

        scope.eval("""
            println("gb top level")
            fun gb_nested() {
                println("gb inside function")
            }
            gb_nested()
        """.trimIndent())

        assertEquals(listOf("gb top level", "gb inside function"), output)
    }

    @Test
    fun testExceptionPrintStackTraceFormatsPrimaryFrameBlock() = runTest {
        val scope = Script.newScope()
        val output = mutableListOf<String>()

        scope.globalBinder().bindGlobalFun("println") {
            val sb = StringBuilder()
            for (i in 0 until args.size) {
                if (i > 0) sb.append(" ")
                sb.append(string(i))
            }
            output.add(sb.toString())
            ObjVoid
        }

        scope.eval(
            """
            fun boom() {
                val arr = [10, 20, 30]
                var a = 10
                var b = arr[a]
                b
            }
            try {
                boom()
            } catch (e) {
                e.printStackTrace()
            }
            """.trimIndent()
        )

        assertTrue(output.isNotEmpty())
        assertEquals(
            "IndexOutOfBoundsException: Index 10 out of bounds for length 3 at eval:4:9:",
            output[0]
        )
        assertEquals("var b = arr[a]", output[1])
        assertEquals("--------^", output[2])
        assertTrue(output.size >= 4)
        assertFalse(output.any { it == "        at eval:4:9: var b = arr[a]" })
    }
}
