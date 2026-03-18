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

package net.sergeych.lyngio.console

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader
import java.io.EOFException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * JVM console implementation:
 * - output/capabilities/input use a single JLine terminal instance
 *   to avoid dual-terminal contention.
 */
object JvmLyngConsole : LyngConsole {
    private const val DEBUG_REVISION = "jline-r26-force-rebuild-on-noop-recovery-2026-03-19"
    private val codeSourceLocation: String by lazy {
        runCatching {
            JvmLyngConsole::class.java.protectionDomain?.codeSource?.location?.toString()
        }.getOrNull() ?: "<unknown>"
    }

    private val terminalRef = AtomicReference<Terminal?>(null)
    private val terminalInitLock = Any()

    private fun currentTerminal(): Terminal? {
        val existing = terminalRef.get()
        if (existing != null) return existing
        synchronized(terminalInitLock) {
            val already = terminalRef.get()
            if (already != null) return already
            val created = buildTerminal()
            if (created != null) terminalRef.set(created)
            return created
        }
    }

    private fun buildTerminal(): Terminal? {
        System.setProperty(TerminalBuilder.PROP_DISABLE_DEPRECATED_PROVIDER_WARNING, "true")

        val providerOrders = listOf(
            "exec",
            "exec,ffm",
            null,
        )
        for (providers in providerOrders) {
            val terminal = runCatching {
                val builder = TerminalBuilder.builder().system(true)
                if (providers != null) builder.providers(providers)
                builder.build()
            }.onFailure {
                if (providers != null) {
                    consoleFlowDebug("jline-events: terminal build failed providers=$providers", it)
                } else {
                    consoleFlowDebug("jline-events: terminal build failed default providers", it)
                }
            }.getOrNull()
            if (terminal != null) {
                val termType = terminal.type.lowercase(Locale.getDefault())
                if (termType.contains("dumb")) {
                    consoleFlowDebug("jline-events: terminal rejected providers=${providers ?: "<default>"} type=${terminal.type}")
                    runCatching { terminal.close() }
                    continue
                }
                consoleFlowDebug("jline-events: terminal built providers=${providers ?: "<default>"} type=${terminal.type}")
                consoleFlowDebug("jline-events: runtime-marker rev=$DEBUG_REVISION codeSource=$codeSourceLocation")
                return terminal
            }
        }
        return null
    }

    private val stateMutex = Mutex()
    private var rawModeRequested: Boolean = false
    private var rawSavedAttributes: Attributes? = null

    private fun enforceRawReadAttrs(term: Terminal) {
        runCatching {
            val attrs = term.attributes
            attrs.setLocalFlag(Attributes.LocalFlag.ICANON, false)
            attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false)
            attrs.setControlChar(Attributes.ControlChar.VMIN, 0)
            attrs.setControlChar(Attributes.ControlChar.VTIME, 1)
            term.setAttributes(attrs)
        }.onFailure {
            consoleFlowDebug("jline-events: enforceRawReadAttrs failed", it)
        }
    }

    override val isSupported: Boolean
        get() = currentTerminal() != null

    override suspend fun isTty(): Boolean {
        val term = currentTerminal() ?: return false
        return !term.type.lowercase(Locale.getDefault()).contains("dumb")
    }

    override suspend fun geometry(): ConsoleGeometry? {
        val term = currentTerminal() ?: return null
        val size = runCatching { term.size }.getOrNull() ?: return null
        if (size.columns <= 0 || size.rows <= 0) return null
        return ConsoleGeometry(size.columns, size.rows)
    }

    override suspend fun ansiLevel(): ConsoleAnsiLevel {
        val colorTerm = (System.getenv("COLORTERM") ?: "").lowercase(Locale.getDefault())
        val term = (System.getenv("TERM") ?: "").lowercase(Locale.getDefault())
        return when {
            colorTerm.contains("truecolor") || colorTerm.contains("24bit") -> ConsoleAnsiLevel.TRUECOLOR
            term.contains("256color") -> ConsoleAnsiLevel.ANSI256
            term.isNotBlank() && term != "dumb" -> ConsoleAnsiLevel.BASIC16
            else -> ConsoleAnsiLevel.NONE
        }
    }

    override suspend fun write(text: String) {
        val term = currentTerminal() ?: return
        term.writer().print(text)
    }

    override suspend fun flush() {
        val term = currentTerminal() ?: return
        term.writer().flush()
    }

    override fun events(): ConsoleEventSource {
        var activeTerm = currentTerminal() ?: return object : ConsoleEventSource {
            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? = null

            override suspend fun close() {}
        }
        val out = Channel<ConsoleEvent>(Channel.UNLIMITED)
        val keyEvents = AtomicLong(0L)
        val keyCodesRead = AtomicLong(0L)
        val keySendFailures = AtomicLong(0L)
        val readFailures = AtomicLong(0L)
        val readerRecoveries = AtomicLong(0L)
        var lastHeartbeat = TimeSource.Monotonic.markNow()
        val keyLoopRunning = AtomicBoolean(true)
        val keyLoopCount = AtomicLong(0L)
        val keyReadStartNs = AtomicLong(0L)
        val keyReadEndNs = AtomicLong(0L)
        val lastKeyReadNs = AtomicLong(System.nanoTime())
        val lastRecoveryNs = AtomicLong(0L)
        val recoveryRequested = AtomicBoolean(false)
        val running = AtomicBoolean(true)
        var winchHandler: Terminal.SignalHandler? = null
        var reader = activeTerm.reader()
        var keyThread: Thread? = null
        var heartbeatThread: Thread? = null

        fun emitResize() {
            val size = runCatching { activeTerm.size }.getOrNull() ?: return
            out.trySend(ConsoleEvent.Resize(size.columns, size.rows))
        }

        fun cleanup() {
            running.set(false)
            keyLoopRunning.set(false)
            runCatching { reader.shutdown() }
            runCatching {
                if (winchHandler != null) {
                    activeTerm.handle(Terminal.Signal.WINCH, winchHandler)
                }
            }.onFailure {
                consoleFlowDebug("jline-events: WINCH handler restore failed", it)
            }
            runCatching { keyThread?.interrupt() }
            runCatching { heartbeatThread?.interrupt() }
            out.close()
        }

        fun installWinchHandler() {
            winchHandler = runCatching {
                activeTerm.handle(Terminal.Signal.WINCH) {
                    emitResize()
                }
            }.onFailure {
                consoleFlowDebug("jline-events: WINCH handler install failed", it)
            }.getOrNull()
        }

        fun tryRebuildTerminal(): Boolean {
            val oldTerm = activeTerm
            val rebuilt = runCatching {
                synchronized(terminalInitLock) {
                    if (terminalRef.get() === oldTerm) {
                        terminalRef.set(null)
                    }
                }
                runCatching { oldTerm.close() }
                    .onFailure { consoleFlowDebug("jline-events: old terminal close failed during rebuild", it) }
                currentTerminal()
            }.onFailure {
                consoleFlowDebug("jline-events: terminal rebuild failed", it)
            }.getOrNull() ?: return false
            if (rebuilt === oldTerm) {
                consoleFlowDebug("jline-events: terminal rebuild returned same terminal instance")
                return false
            }
            activeTerm = rebuilt
            reader = activeTerm.reader()
            val rawRequestedNow = runCatching { stateMutex.tryLock() }.getOrNull() == true && try {
                rawModeRequested
            } finally {
                stateMutex.unlock()
            }
            if (rawRequestedNow) {
                val saved = runCatching { activeTerm.enterRawMode() }.getOrNull()
                if (saved != null) {
                    enforceRawReadAttrs(activeTerm)
                    if (runCatching { stateMutex.tryLock() }.getOrNull() == true) {
                        try {
                            rawSavedAttributes = saved
                        } finally {
                            stateMutex.unlock()
                        }
                    }
                } else {
                    consoleFlowDebug("jline-events: terminal rebuild succeeded but enterRawMode failed")
                }
            }
            installWinchHandler()
            emitResize()
            consoleFlowDebug("jline-events: terminal rebuilt and rebound")
            return true
        }

        consoleFlowDebug("jline-events: collector started rev=$DEBUG_REVISION")
        emitResize()
        installWinchHandler()

        keyThread = thread(start = true, isDaemon = true, name = "lyng-jline-key-reader") {
            consoleFlowDebug("jline-events: key-reader thread started")
            consoleFlowDebug("jline-events: using NonBlockingReader key path")
            while (running.get() && keyLoopRunning.get()) {
                keyLoopCount.incrementAndGet()
                try {
                    if (recoveryRequested.compareAndSet(true, false)) {
                        val prevReader = reader
                        runCatching { prevReader.shutdown() }
                            .onFailure { consoleFlowDebug("jline-events: reader shutdown failed during recovery", it) }

                        reader = activeTerm.reader()
                        if (reader.hashCode() == prevReader.hashCode()) {
                            consoleFlowDebug("jline-events: reader recovery no-op oldReader=${prevReader.hashCode()} newReader=${reader.hashCode()} -> forcing terminal rebuild")
                            if (!tryRebuildTerminal()) {
                                consoleFlowDebug("jline-events: forced terminal rebuild did not produce a new reader")
                            }
                        } else {
                            consoleFlowDebug("jline-events: reader recovered oldReader=${prevReader.hashCode()} newReader=${reader.hashCode()}")
                        }

                        readerRecoveries.incrementAndGet()
                        lastRecoveryNs.set(System.nanoTime())
                    }

                    val isRaw = runCatching { stateMutex.tryLock() }.getOrNull() == true && try {
                        rawModeRequested
                    } finally {
                        stateMutex.unlock()
                    }
                    if (!isRaw) {
                        Thread.sleep(20)
                        continue
                    }
                    keyReadStartNs.set(System.nanoTime())
                    val event = readKeyEvent(reader)
                    keyReadEndNs.set(System.nanoTime())
                    if (event == null) {
                        continue
                    }
                    keyCodesRead.incrementAndGet()
                    lastKeyReadNs.set(System.nanoTime())
                    if (out.trySend(event).isSuccess) {
                        keyEvents.incrementAndGet()
                    } else {
                        keySendFailures.incrementAndGet()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    readFailures.incrementAndGet()
                    consoleFlowDebug("jline-events: blocking read failed", e)
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        heartbeatThread = thread(start = true, isDaemon = true, name = "lyng-jline-heartbeat") {
            while (running.get()) {
                if (lastHeartbeat.elapsedNow() >= 2.seconds) {
                    val requested = runCatching { stateMutex.tryLock() }.getOrNull() == true && try {
                        rawModeRequested
                    } finally {
                        stateMutex.unlock()
                    }
                    val readStartNs = keyReadStartNs.get()
                    val readEndNs = keyReadEndNs.get()
                    val lastKeyNs = lastKeyReadNs.get()
                    val idleMs = if (lastKeyNs > 0L) (System.nanoTime() - lastKeyNs) / 1_000_000L else 0L
                    val readBlockedMs = if (readStartNs > 0L && readEndNs < readStartNs) {
                        (System.nanoTime() - readStartNs) / 1_000_000L
                    } else 0L
                    if (requested && keyCodesRead.get() > 0L && idleMs >= 1400L) {
                        val sinceRecoveryMs = (System.nanoTime() - lastRecoveryNs.get()) / 1_000_000L
                        if (sinceRecoveryMs >= 1200L) {
                            recoveryRequested.set(true)
                            consoleFlowDebug("jline-events: key stream idle ${idleMs}ms; scheduling reader recovery")
                        }
                    }
                    consoleFlowDebug(
                        "jline-events: heartbeat keyCodes=${keyCodesRead.get()} keysSent=${keyEvents.get()} sendFailures=${keySendFailures.get()} readFailures=${readFailures.get()} recoveries=${readerRecoveries.get()} rawRequested=$requested keyLoop=${keyLoopCount.get()} readBlockedMs=$readBlockedMs keyIdleMs=$idleMs keyPath=reader"
                    )
                    lastHeartbeat = TimeSource.Monotonic.markNow()
                }
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        return object : ConsoleEventSource {
            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? {
                if (!running.get()) return null
                if (timeoutMs <= 0L) {
                    return out.receiveCatching().getOrNull()
                }
                return withTimeoutOrNull(timeoutMs.milliseconds) {
                    out.receiveCatching().getOrNull()
                }
            }

            override suspend fun close() {
                cleanup()
                consoleFlowDebug(
                    "jline-events: collector ended keys=${keyEvents.get()} readFailures=${readFailures.get()}"
                )
            }
        }
    }

    override suspend fun setRawMode(enabled: Boolean): Boolean {
        val term = currentTerminal() ?: return false
        return stateMutex.withLock {
            if (enabled) {
                if (rawModeRequested) return@withLock false
                val saved = runCatching { term.enterRawMode() }.getOrNull() ?: return@withLock false
                enforceRawReadAttrs(term)
                rawSavedAttributes = saved
                rawModeRequested = true
                consoleFlowDebug("jline-events: setRawMode(true): enabled")
                true
            } else {
                val hadRaw = rawModeRequested
                rawModeRequested = false
                val saved = rawSavedAttributes
                rawSavedAttributes = null
                runCatching {
                    if (saved != null) term.setAttributes(saved)
                }.onFailure {
                    consoleFlowDebug("jline-events: setRawMode(false): restore failed", it)
                }
                consoleFlowDebug("jline-events: setRawMode(false): disabled hadRaw=$hadRaw")
                hadRaw
            }
        }
    }

    private fun readKeyEvent(reader: NonBlockingReader): ConsoleEvent.KeyDown? {
        val code = reader.read(120L)
        if (code == NonBlockingReader.READ_EXPIRED) return null
        if (code < 0) throw EOFException("non-blocking reader returned EOF")
        return decodeKey(code) { timeout -> readNextCode(reader, timeout) }
    }

    private fun decodeKey(code: Int, nextCode: (Long) -> Int?): ConsoleEvent.KeyDown {
        if (code == 27) {
            val next = nextCode(25L)
            if (next == null || next < 0) {
                return key("Escape")
            }
            if (next == '['.code || next == 'O'.code) {
                val sb = StringBuilder()
                sb.append(next.toChar())
                var i = 0
                while (i < 6) {
                    val c = nextCode(25L) ?: break
                    if (c < 0) break
                    sb.append(c.toChar())
                    if (c.toChar().isLetter() || c == '~'.code) break
                    i += 1
                }
                return keyFromAnsiSequence(sb.toString()) ?: key("Escape")
            }
            // Alt+key
            val base = decodePlainKey(next)
            return ConsoleEvent.KeyDown(
                key = base.key,
                code = base.code,
                ctrl = base.ctrl,
                alt = true,
                shift = base.shift,
                meta = false
            )
        }
        return decodePlainKey(code)
    }

    private fun readNextCode(reader: NonBlockingReader, timeoutMs: Long): Int? {
        val c = reader.read(timeoutMs)
        if (c == NonBlockingReader.READ_EXPIRED) return null
        if (c < 0) throw EOFException("non-blocking reader returned EOF while decoding key sequence")
        return c
    }


    private fun decodePlainKey(code: Int): ConsoleEvent.KeyDown = when (code) {
        3 -> key("c", ctrl = true)
        9 -> key("Tab")
        10, 13 -> key("Enter")
        127, 8 -> key("Backspace")
        32 -> key(" ")
        else -> {
            if (code in 1..26) {
                val ch = ('a'.code + code - 1).toChar().toString()
                key(ch, ctrl = true)
            } else {
                val ch = code.toChar().toString()
                key(ch, shift = ch.length == 1 && ch[0].isLetter() && ch[0].isUpperCase())
            }
        }
    }

    private fun keyFromAnsiSequence(seq: String): ConsoleEvent.KeyDown? = when (seq) {
        "[A", "OA" -> key("ArrowUp")
        "[B", "OB" -> key("ArrowDown")
        "[C", "OC" -> key("ArrowRight")
        "[D", "OD" -> key("ArrowLeft")
        "[H", "OH" -> key("Home")
        "[F", "OF" -> key("End")
        "[2~" -> key("Insert")
        "[3~" -> key("Delete")
        "[5~" -> key("PageUp")
        "[6~" -> key("PageDown")
        else -> null
    }

    private fun key(
        value: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): ConsoleEvent.KeyDown = ConsoleEvent.KeyDown(
        key = value,
        code = null,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
        meta = false
    )
}
