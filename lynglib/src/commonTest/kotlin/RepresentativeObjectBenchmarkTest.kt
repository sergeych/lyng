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
import net.sergeych.lyng.BytecodeBodyProvider
import net.sergeych.lyng.Script
import net.sergeych.lyng.Statement
import net.sergeych.lyng.bytecode.*
import net.sergeych.lyng.obj.ObjInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

class RepresentativeObjectBenchmarkTest {
    @Test
    fun benchmarkObjectOps() = runTest {
        if (!Benchmarks.enabled) return@runTest
        val iterations = 20000
        val script = """
            fun objectBench(n) {
                var acc = 0
                var s = ""
                val list = ["a","b","c","d","e","f","g","h","i","j"]
                val rlist = [1.0,2.0,3.0,4.0,5.0]
                val map = {"a":1, "b":2, "c":3, "d":4}
                var i = 0
                while(i < n) {
                    val k = list[i % 10]
                    val v = map[k] ?: 0
                    val j = i % 10
                    val r = i * 0.5
                    val rr = rlist[j % 5]
                    val oi: Object = i
                    val oj: Object = j
                    val or: Object = r
                    val or2: Object = r + 1.0
                    s = s + k
                    if( k in list ) acc += 1
                    if( k == "a" ) acc += v else acc -= 1
                    if( k != "z" ) acc += 1
                    if( k < "m" ) acc += 1 else acc -= 1
                    if( k >= "d" ) acc += 1 else acc -= 1
                    if( j < 7 ) acc += 1 else acc -= 1
                    if( j >= 3 ) acc += 1 else acc -= 1
                    if( r <= 2.5 ) acc += 1 else acc -= 1
                    if( r > 3.5 ) acc += 1 else acc -= 1
                    if( rr < 3.5 ) acc += 1 else acc -= 1
                    if( rr >= 2.5 ) acc += 1 else acc -= 1
                    if( oi == oj ) acc += 1 else acc -= 1
                    if( oi < oj ) acc += 1 else acc -= 1
                    if( or > or2 ) acc += 1 else acc -= 1
                    if( or <= or2 ) acc += 1 else acc -= 1
                    if( i % 4 == 0 ) acc += list.size
                    i++
                }
                acc + s.size
            }
        """.trimIndent()

        val scope = Script.newScope()
        scope.eval(script)
        val expected = expectedValue(iterations)
        dumpFunctionBytecode(scope, "objectBench")
        dumpFastCompareStats(scope, "objectBench")

        val start = TimeSource.Monotonic.markNow()
        val result = scope.eval("objectBench($iterations)") as ObjInt
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        println("[DEBUG_LOG] [BENCH] object-ops elapsed=${elapsedMs} ms")
        assertEquals(expected, result.value)
    }

    private fun expectedValue(iterations: Int): Long {
        val list = arrayOf("a","b","c","d","e","f","g","h","i","j")
        val rlist = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val map = mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4)
        var acc = 0L
        var s = ""
        for (i in 0 until iterations) {
            val k = list[i % list.size]
            val v = map[k] ?: 0
            val j = i % 10
            val r = i * 0.5
            val rr = rlist[j % 5]
            val oi = i
            val oj = j
            val or = r
            val or2 = r + 1.0
            s += k
            acc += 1
            if (k == "a") acc += v else acc -= 1
            if (k != "z") acc += 1
            if (k < "m") acc += 1 else acc -= 1
            if (k >= "d") acc += 1 else acc -= 1
            if (j < 7) acc += 1 else acc -= 1
            if (j >= 3) acc += 1 else acc -= 1
            if (r <= 2.5) acc += 1 else acc -= 1
            if (r > 3.5) acc += 1 else acc -= 1
            if (rr < 3.5) acc += 1 else acc -= 1
            if (rr >= 2.5) acc += 1 else acc -= 1
            if (oi == oj) acc += 1 else acc -= 1
            if (oi < oj) acc += 1 else acc -= 1
            if (or > or2) acc += 1 else acc -= 1
            if (or <= or2) acc += 1 else acc -= 1
            if (i % 4 == 0) acc += list.size
        }
        return acc + s.length
    }

    private fun dumpFunctionBytecode(scope: net.sergeych.lyng.Scope, name: String) {
        val disasm = scope.disassembleSymbol(name)
        println("[DEBUG_LOG] [BENCH] object-ops cmd:\n$disasm")
    }

    private fun dumpFastCompareStats(scope: net.sergeych.lyng.Scope, name: String) {
        val fn = resolveBytecodeFunction(scope, name) ?: return
        var strLocal = 0
        var intObjLocal = 0
        var realObjLocal = 0
        for (cmd in fn.cmds) {
            when (cmd) {
                is CmdCmpEqStrLocal,
                is CmdCmpNeqStrLocal,
                is CmdCmpLtStrLocal,
                is CmdCmpGteStrLocal -> strLocal += 1
                is CmdCmpEqIntObjLocal,
                is CmdCmpNeqIntObjLocal,
                is CmdCmpLtIntObjLocal,
                is CmdCmpGteIntObjLocal -> intObjLocal += 1
                is CmdCmpEqRealObjLocal,
                is CmdCmpNeqRealObjLocal,
                is CmdCmpLtRealObjLocal,
                is CmdCmpGteRealObjLocal -> realObjLocal += 1
                else -> {}
            }
        }
        println(
            "[DEBUG_LOG] [BENCH] object-ops fast-compare local cmds: str=$strLocal intObj=$intObjLocal realObj=$realObjLocal"
        )
    }

    private fun resolveBytecodeFunction(scope: net.sergeych.lyng.Scope, name: String): CmdFunction? {
        val record = scope.get(name) ?: return null
        val stmt = record.value as? Statement ?: return null
        return (stmt as? BytecodeStatement)?.bytecodeFunction()
            ?: (stmt as? BytecodeBodyProvider)?.bytecodeBody()?.bytecodeFunction()
    }
}
