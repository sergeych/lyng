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
import net.sergeych.lyng.*
import net.sergeych.lyng.bytecode.*
import net.sergeych.lyng.obj.ObjString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

class PiSpigotBenchmarkTest {
    @Test
    fun benchmarkPiSpigot() = runTest {
        if (!Benchmarks.enabled) return@runTest

        val source = loadPiSpigotSource()

        val digits = 200
        val expectedSuffix = "49303819"

        val saved = PerfProfiles.snapshot()
        PerfFlags.RVAL_FASTPATH = false
        val optimizedRvalOffElapsed = runCase(
            "optimized-int-division-rval-off",
            source,
            digits,
            expectedSuffix,
            dumpBytecode = false
        )
        PerfProfiles.restore(saved)
        val optimizedElapsed = runCase("optimized-int-division-rval-on", source, digits, expectedSuffix, dumpBytecode = true)
        val runtimeSpeedup = optimizedRvalOffElapsed.toDouble() / optimizedElapsed.toDouble()
        println(
            "[DEBUG_LOG] [BENCH] pi-spigot compare n=$digits " +
                "rvalOff=${optimizedRvalOffElapsed} ms rvalOn=${optimizedElapsed} ms " +
                "rvalSpeedup=${"%.2f".format(runtimeSpeedup)}x"
        )
    }

    private suspend fun runCase(
        name: String,
        source: String,
        digits: Int,
        expectedSuffix: String,
        dumpBytecode: Boolean,
    ): Long {
        val scope = Script.newScope()
        scope.eval(source)

        if (dumpBytecode) {
            println("[DEBUG_LOG] [BENCH] pi-spigot cmd:\n${scope.disassembleSymbol("piSpigot")}")
            dumpHotOps(scope, "piSpigot")
        }

        val first = scope.eval("piSpigot(0, $digits)") as ObjString
        assertEquals(expectedSuffix, first.value)

        repeat(2) {
            val warm = scope.eval("piSpigot(0, $digits)") as ObjString
            assertEquals(expectedSuffix, warm.value)
        }

        val iterations = 3
        val start = TimeSource.Monotonic.markNow()
        repeat(iterations) {
            val result = scope.eval("piSpigot(0, $digits)") as ObjString
            assertEquals(expectedSuffix, result.value)
        }
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        val avgMs = elapsedMs.toDouble() / iterations.toDouble()
        println(
            "[DEBUG_LOG] [BENCH] pi-spigot $name n=$digits iterations=$iterations " +
                "elapsed=${elapsedMs} ms avg=${"%.2f".format(avgMs)} ms"
        )
        return elapsedMs
    }

    private fun dumpHotOps(scope: net.sergeych.lyng.Scope, name: String) {
        val fn = resolveBytecodeFunction(scope, name) ?: return
        val makeRange = fn.cmds.count { it is CmdMakeRange }
        val callMemberSlot = fn.cmds.count { it is CmdCallMemberSlot }
        val iterPush = fn.cmds.count { it is CmdIterPush }
        val getIndex = fn.cmds.count { it is CmdGetIndex }
        val setIndex = fn.cmds.count { it is CmdSetIndex }
        println(
            "[DEBUG_LOG] [BENCH] pi-spigot hot-ops " +
                "makeRange=$makeRange callMemberSlot=$callMemberSlot iterPush=$iterPush " +
                "getIndex=$getIndex setIndex=$setIndex total=${fn.cmds.size}"
        )
    }

    private fun resolveBytecodeFunction(scope: net.sergeych.lyng.Scope, name: String): CmdFunction? {
        val record = scope.get(name) ?: return null
        val stmt = record.value as? Statement ?: return null
        return (stmt as? BytecodeStatement)?.bytecodeFunction()
            ?: (stmt as? BytecodeBodyProvider)?.bytecodeBody()?.bytecodeFunction()
    }

    private fun loadPiSpigotSource(): String {
        val source = Files.readString(resolveExample("pi-bench.lyng"))
        return source.substringBefore("\nval t0 = Instant()")
    }

    private fun resolveExample(name: String): Path {
        val direct = Path.of("examples", name)
        if (Files.exists(direct)) return direct
        val parent = Path.of("..", "examples", name)
        if (Files.exists(parent)) return parent
        error("example not found: $name")
    }
}
