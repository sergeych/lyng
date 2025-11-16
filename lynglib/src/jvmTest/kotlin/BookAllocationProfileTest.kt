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
import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test

class BookAllocationProfileTest {

    private fun outFile(): File = File("lynglib/build/book_alloc_profile.txt")

    private fun writeHeader(f: File) {
        if (!f.parentFile.exists()) f.parentFile.mkdirs()
        f.writeText("[DEBUG_LOG] Book allocation/time profiling (JVM)\n")
        f.appendText("[DEBUG_LOG] All sizes in bytes; time in ns (lower is better).\n")
    }

    private fun appendLine(f: File, s: String) { f.appendText(s + "\n") }

    // Optional STDERR filter to hide benign warnings during profiling runs
    private inline fun <T> withFilteredStderr(vararg suppressContains: String, block: () -> T): T {
        val orig = System.err
        val filtering = java.io.PrintStream(object : java.io.OutputStream() {
            private val buf = StringBuilder()
            override fun write(b: Int) {
                if (b == '\n'.code) {
                    val line = buf.toString()
                    val suppress = suppressContains.any { line.contains(it) }
                    if (!suppress) orig.println(line)
                    buf.setLength(0)
                } else buf.append(b.toChar())
            }
        })
        return try {
            System.setErr(filtering)
            block()
        } finally {
            System.setErr(orig)
        }
    }

    private fun forceGc() {
        // Best-effort GC to stabilize measurements
        repeat(3) {
            System.gc()
            try { Thread.sleep(25) } catch (_: InterruptedException) {}
        }
    }

    private fun usedHeap(): Long {
        val mem = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        return mem.used
    }

    private suspend fun runBooksOnce(): Unit = runBlocking {
        // Mirror BookTest set
        runDocTests("../docs/tutorial.md")
        runDocTests("../docs/math.md")
        runDocTests("../docs/advanced_topics.md")
        runDocTests("../docs/OOP.md")
        runDocTests("../docs/Real.md")
        runDocTests("../docs/List.md")
        runDocTests("../docs/Range.md")
        runDocTests("../docs/Set.md")
        runDocTests("../docs/Map.md")
        runDocTests("../docs/Buffer.md")
        // Samples folder, bookMode=true
        for (bt in Files.list(Paths.get("../docs/samples")).toList()) {
            if (bt.extension == "md") runDocTests(bt.toString(), bookMode = true)
        }
        runDocTests("../docs/declaring_arguments.md")
        runDocTests("../docs/exceptions_handling.md")
        runDocTests("../docs/time.md")
        runDocTests("../docs/parallelism.md")
        runDocTests("../docs/RingBuffer.md")
        runDocTests("../docs/Iterable.md")
    }

    private data class ProfileResult(val timeNs: Long, val allocBytes: Long)

    private suspend fun profileRun(): ProfileResult {
        forceGc()
        val before = usedHeap()
        val elapsed = measureNanoTime {
            withFilteredStderr("ScriptFlowIsNoMoreCollected") {
                runBooksOnce()
            }
        }
        forceGc()
        val after = usedHeap()
        val alloc = (after - before).coerceAtLeast(0)
        return ProfileResult(elapsed, alloc)
    }

    private data class GcSnapshot(val count: Long, val timeMs: Long)
    private fun gcSnapshot(): GcSnapshot {
        var c = 0L
        var t = 0L
        for (gc: GarbageCollectorMXBean in ManagementFactory.getGarbageCollectorMXBeans()) {
            c += (gc.collectionCount.takeIf { it >= 0 } ?: 0)
            t += (gc.collectionTime.takeIf { it >= 0 } ?: 0)
        }
        return GcSnapshot(c, t)
    }

    // --- Optional JFR support via reflection (works only on JDKs with Flight Recorder) ---
    private class JfrHandle(val rec: Any, val dump: (File) -> Unit, val stop: () -> Unit)

    private fun jfrStartIfRequested(name: String): JfrHandle? {
        val enabled = System.getProperty("lyng.jfr")?.toBoolean() == true
        if (!enabled) return null
        return try {
            val recCl = Class.forName("jdk.jfr.Recording")
            val ctor = recCl.getDeclaredConstructor()
            val rec = ctor.newInstance()
            val setName = recCl.methods.firstOrNull { it.name == "setName" && it.parameterTypes.size == 1 }
            setName?.invoke(rec, "Lyng-$name")
            val start = recCl.methods.first { it.name == "start" && it.parameterTypes.isEmpty() }
            start.invoke(rec)
            val stop = recCl.methods.first { it.name == "stop" && it.parameterTypes.isEmpty() }
            val dump = recCl.methods.firstOrNull { it.name == "dump" && it.parameterTypes.size == 1 }
            val dumper: (File) -> Unit = if (dump != null) {
                { f -> dump.invoke(rec, f.toPath()) }
            } else {
                { _ -> }
            }
            JfrHandle(rec, dumper) { stop.invoke(rec) }
        } catch (e: Throwable) {
            // JFR requested but not available; note once via stdout and proceed without it
            try {
                println("[DEBUG_LOG] JFR not available on this JVM; run with Oracle/OpenJDK 11+ to enable -Dlyng.jfr=true")
            } catch (_: Throwable) {}
            null
        }
    }

    private fun intProp(name: String, def: Int): Int =
        System.getProperty(name)?.toIntOrNull() ?: def

    private fun boolProp(name: String, def: Boolean): Boolean =
        System.getProperty(name)?.toBoolean() ?: def

    private data class FlagSnapshot(
        val RVAL_FASTPATH: Boolean,
        val PRIMITIVE_FASTOPS: Boolean,
        val ARG_BUILDER: Boolean,
        val ARG_SMALL_ARITY_12: Boolean,
        val FIELD_PIC: Boolean,
        val METHOD_PIC: Boolean,
        val FIELD_PIC_SIZE_4: Boolean,
        val METHOD_PIC_SIZE_4: Boolean,
        val INDEX_PIC: Boolean,
        val INDEX_PIC_SIZE_4: Boolean,
        val SCOPE_POOL: Boolean,
        val PIC_DEBUG_COUNTERS: Boolean,
    ) {
        fun restore() {
            PerfFlags.RVAL_FASTPATH = RVAL_FASTPATH
            PerfFlags.PRIMITIVE_FASTOPS = PRIMITIVE_FASTOPS
            PerfFlags.ARG_BUILDER = ARG_BUILDER
            PerfFlags.ARG_SMALL_ARITY_12 = ARG_SMALL_ARITY_12
            PerfFlags.FIELD_PIC = FIELD_PIC
            PerfFlags.METHOD_PIC = METHOD_PIC
            PerfFlags.FIELD_PIC_SIZE_4 = FIELD_PIC_SIZE_4
            PerfFlags.METHOD_PIC_SIZE_4 = METHOD_PIC_SIZE_4
            PerfFlags.INDEX_PIC = INDEX_PIC
            PerfFlags.INDEX_PIC_SIZE_4 = INDEX_PIC_SIZE_4
            PerfFlags.SCOPE_POOL = SCOPE_POOL
            PerfFlags.PIC_DEBUG_COUNTERS = PIC_DEBUG_COUNTERS
        }
    }

    private fun snapshotFlags() = FlagSnapshot(
        RVAL_FASTPATH = PerfFlags.RVAL_FASTPATH,
        PRIMITIVE_FASTOPS = PerfFlags.PRIMITIVE_FASTOPS,
        ARG_BUILDER = PerfFlags.ARG_BUILDER,
        ARG_SMALL_ARITY_12 = PerfFlags.ARG_SMALL_ARITY_12,
        FIELD_PIC = PerfFlags.FIELD_PIC,
        METHOD_PIC = PerfFlags.METHOD_PIC,
        FIELD_PIC_SIZE_4 = PerfFlags.FIELD_PIC_SIZE_4,
        METHOD_PIC_SIZE_4 = PerfFlags.METHOD_PIC_SIZE_4,
        INDEX_PIC = PerfFlags.INDEX_PIC,
        INDEX_PIC_SIZE_4 = PerfFlags.INDEX_PIC_SIZE_4,
        SCOPE_POOL = PerfFlags.SCOPE_POOL,
        PIC_DEBUG_COUNTERS = PerfFlags.PIC_DEBUG_COUNTERS,
    )

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else ((s[mid - 1] + s[mid]) / 2)
    }

    private suspend fun runScenario(
        name: String,
        prepare: () -> Unit,
        repeats: Int = 3,
        out: (String) -> Unit
    ): ProfileResult {
        val warmup = intProp("lyng.profile.warmup", 1)
        val reps = intProp("lyng.profile.repeats", repeats)
        // JFR
        val jfr = jfrStartIfRequested(name)
        if (System.getProperty("lyng.jfr")?.toBoolean() == true && jfr == null) {
            out("[DEBUG_LOG]  JFR: requested but not available on this JVM")
        }
        // Warm-up before GC snapshot (some profilers prefer this)
        prepare()
        repeat(warmup) { profileRun() }
        // GC baseline
        val gc0 = gcSnapshot()
        val times = ArrayList<Long>(repeats)
        val allocs = ArrayList<Long>(repeats)
        repeat(reps) {
            val r = profileRun()
            times += r.timeNs
            allocs += r.allocBytes
        }
        val pr = ProfileResult(median(times), median(allocs))
        val gc1 = gcSnapshot()
        val gcCountDelta = (gc1.count - gc0.count).coerceAtLeast(0)
        val gcTimeDelta = (gc1.timeMs - gc0.timeMs).coerceAtLeast(0)
        out("[DEBUG_LOG]  time=${pr.timeNs} ns, alloc=${pr.allocBytes} B (median of ${reps}), GC(count=${gcCountDelta}, timeMs=${gcTimeDelta})")
        // Stop and dump JFR if enabled
        if (jfr != null) {
            try {
                jfr.stop()
                val dumpFile = File("lynglib/build/jfr_${name}.jfr")
                jfr.dump(dumpFile)
                out("[DEBUG_LOG]  JFR dumped: ${dumpFile.path}")
            } catch (_: Throwable) {}
        }
        return pr
    }

    @Test
    fun profile_books_allocations_and_time() = runTestBlocking {
        val f = outFile()
        writeHeader(f)

        fun log(s: String) = appendLine(f, s)

        val saved = snapshotFlags()
        try {
            data class Scenario(val label: String, val title: String, val prep: () -> Unit)
            val scenarios = mutableListOf<Scenario>()
            // Baseline A
            scenarios += Scenario("A", "JVM defaults") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false
            }
            // Most flags OFF B
            scenarios += Scenario("B", "most perf flags OFF") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false
                PerfFlags.RVAL_FASTPATH = false
                PerfFlags.PRIMITIVE_FASTOPS = false
                PerfFlags.ARG_BUILDER = false
                PerfFlags.ARG_SMALL_ARITY_12 = false
                PerfFlags.FIELD_PIC = false
                PerfFlags.METHOD_PIC = false
                PerfFlags.FIELD_PIC_SIZE_4 = false
                PerfFlags.METHOD_PIC_SIZE_4 = false
                PerfFlags.INDEX_PIC = false
                PerfFlags.INDEX_PIC_SIZE_4 = false
                PerfFlags.SCOPE_POOL = false
            }
            // Defaults with INDEX_PIC size 2 C
            scenarios += Scenario("C", "defaults except INDEX_PIC_SIZE_4=false") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false
                PerfFlags.INDEX_PIC = true; PerfFlags.INDEX_PIC_SIZE_4 = false
            }

            // One-flag toggles relative to A
            scenarios += Scenario("D", "A with RVAL_FASTPATH=false") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false; PerfFlags.RVAL_FASTPATH = false
            }
            scenarios += Scenario("E", "A with PRIMITIVE_FASTOPS=false") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false; PerfFlags.PRIMITIVE_FASTOPS = false
            }
            scenarios += Scenario("F", "A with INDEX_PIC=false") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false; PerfFlags.INDEX_PIC = false
            }
            scenarios += Scenario("G", "A with SCOPE_POOL=false") {
                saved.restore(); PerfFlags.PIC_DEBUG_COUNTERS = false; PerfFlags.SCOPE_POOL = false
            }

            val shuffle = boolProp("lyng.profile.shuffle", true)
            val order = if (shuffle) scenarios.shuffled(Random(System.nanoTime())) else scenarios

            val results = mutableMapOf<String, ProfileResult>()
            for (sc in order) {
                log("[DEBUG_LOG] Scenario ${sc.label}: ${sc.title}")
                results[sc.label] = runScenario(sc.label, prepare = sc.prep, out = ::log)
            }

            // Summary vs A if measured
            val a = results["A"]
            if (a != null) {
                log("[DEBUG_LOG] Summary deltas vs A (medians):")
                fun deltaLine(name: String, r: ProfileResult) = "[DEBUG_LOG]  ${name} - A: time=${r.timeNs - a.timeNs} ns, alloc=${r.allocBytes - a.allocBytes} B"
                listOf("B","C","D","E","F","G").forEach { k ->
                    results[k]?.let { r -> log(deltaLine(k, r)) }
                }
            }
        } finally {
            saved.restore()
        }
    }
}

// Minimal runBlocking bridge to avoid extra test deps here
private fun runTestBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking { block() }
}
