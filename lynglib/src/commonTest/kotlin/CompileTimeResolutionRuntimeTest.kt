/*
 * Copyright 2026 Sergey S. Chernov
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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.toInt
import kotlin.test.Test
import kotlin.test.assertEquals

class CompileTimeResolutionRuntimeTest {

    @Test
    fun strictSlotRefsAllowCapturedLocals() = runTest {
        val code = """
            fun outer() {
                var x = 1
                fun inner() { x = 3; x }
                inner()
            }
            outer()
        """.trimIndent()
        val script = Compiler.compileWithResolution(
            Source("<strict-slot>", code),
            Script.defaultImportManager,
            useBytecodeStatements = false,
            strictSlotRefs = true
        )
        val result = script.execute(Script.defaultImportManager.newStdScope())
        assertEquals(3, result.toInt())
    }

    @Test
    fun bytecodeRespectsShadowingInNestedBlock() = runTest {
        val code = """
            fun outer() {
                var x = 1
                val y = {
                    var x = 10
                    x + 1
                }
                y() + x
            }
            outer()
        """.trimIndent()
        val script = Compiler.compileWithResolution(
            Source("<shadow-slot>", code),
            Script.defaultImportManager,
            useBytecodeStatements = true,
            strictSlotRefs = true
        )
        val result = script.execute(Script.defaultImportManager.newStdScope())
        assertEquals(12, result.toInt())
    }

    @Test
    fun bytecodeRespectsShadowingInBlockStatement() = runTest {
        val code = """
            fun outer() {
                var x = 1
                var y = 0
                if (true) {
                    var x = 10
                    y = x + 1
                }
                y + x
            }
            outer()
        """.trimIndent()
        val script = Compiler.compileWithResolution(
            Source("<shadow-block>", code),
            Script.defaultImportManager,
            useBytecodeStatements = true,
            strictSlotRefs = true
        )
        val result = script.execute(Script.defaultImportManager.newStdScope())
        assertEquals(12, result.toInt(), "result=${result.toInt()}")
    }
}
