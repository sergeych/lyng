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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Benchmarks
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ForInStatement
import net.sergeych.lyng.Script
import net.sergeych.lyng.Statement
import net.sergeych.lyng.bytecode.BytecodeDisassembler
import net.sergeych.lyng.bytecode.BytecodeStatement
import net.sergeych.lyng.obj.ObjInt
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals

class NestedRangeBenchmarkTest {
    @Test
    fun benchmarkHappyNumbersNestedRanges() = runTest {
        if (!Benchmarks.enabled) return@runTest
        val bodyScript = """
            var count = 0
            for( n1 in 0..9 )
                for( n2 in 0..9 )
                    for( n3 in 0..9 )
                        for( n4 in 0..9 )
                            for( n5 in 0..9 )
                                for( n6 in 0..9 )
                                    if( n1 + n2 + n3 == n4 + n5 + n6 ) count++
            count
        """.trimIndent()

        val compiled = Compiler.compile(bodyScript)
        dumpNestedLoopBytecode(compiled.debugStatements())

        val script = """
            fun naiveCountHappyNumbers() {
                $bodyScript
            }
        """.trimIndent()

        val scope = Script.newScope()
        scope.eval(script)
        val fnDisasm = scope.disassembleSymbol("naiveCountHappyNumbers")
        println("[DEBUG_LOG] [BENCH] nested-happy function naiveCountHappyNumbers bytecode:\n$fnDisasm")

        val start = TimeSource.Monotonic.markNow()
        val result = scope.eval("naiveCountHappyNumbers()") as ObjInt
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        println("[DEBUG_LOG] [BENCH] nested-happy elapsed=${elapsedMs} ms")
        assertEquals(55252L, result.value)
    }

    private fun dumpNestedLoopBytecode(statements: List<Statement>) {
        var current: Statement? = statements.firstOrNull { stmt ->
            stmt is BytecodeStatement && stmt.original is ForInStatement
        }
        var depth = 1
        while (current is BytecodeStatement && current.original is ForInStatement) {
            val original = current.original as ForInStatement
            println(
                "[DEBUG_LOG] [BENCH] nested-happy loop depth=$depth " +
                    "constRange=${original.constRange} canBreak=${original.canBreak} " +
                    "loopSlotPlan=${original.loopSlotPlan}"
            )
            val fn = current.bytecodeFunction()
            val slots = fn.scopeSlotNames.mapIndexed { idx, name ->
                val slotName = name ?: "s$idx"
                "$slotName@${fn.scopeSlotDepths[idx]}:${fn.scopeSlotIndices[idx]}"
            }
            println("[DEBUG_LOG] [BENCH] nested-happy slots depth=$depth: ${slots.joinToString(", ")}")
            val disasm = BytecodeDisassembler.disassemble(fn)
            println("[DEBUG_LOG] [BENCH] nested-happy bytecode depth=$depth:\n$disasm")
            current = original.body
            depth += 1
        }
        if (depth == 1) {
            println("[DEBUG_LOG] [BENCH] nested-happy bytecode: <not found>")
        }
    }

}
