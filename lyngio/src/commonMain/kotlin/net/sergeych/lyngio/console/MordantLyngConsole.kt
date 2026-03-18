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

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawModeOrNull
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.ScriptFlowIsNoMoreCollected
import net.sergeych.mp_tools.globalLaunch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Mordant-backed console runtime implementation.
 */
object MordantLyngConsole : LyngConsole {
    private val terminal: Terminal? by lazy {
        runCatching { Terminal() }.getOrNull()
    }

    private val stateMutex = Mutex()
    private var rawModeRequested: Boolean = false
    private var rawModeScope: RawModeScope? = null

    private suspend fun forceRawModeReset(t: Terminal): Boolean = stateMutex.withLock {
        if (!rawModeRequested) return@withLock false
        runCatching { rawModeScope?.close() }
            .onFailure { consoleFlowDebug("forceRawModeReset: close failed", it) }
        rawModeScope = null
        rawModeRequested = false
        if (!t.terminalInfo.inputInteractive) return@withLock false
        val reopened = t.enterRawModeOrNull()
        rawModeScope = reopened
        rawModeRequested = reopened != null
        reopened != null
    }

    override val isSupported: Boolean
        get() = terminal != null

    override suspend fun isTty(): Boolean {
        val t = terminal ?: return false
        return t.terminalInfo.outputInteractive
    }

    override suspend fun geometry(): ConsoleGeometry? {
        val t = terminal ?: return null
        val size = t.updateSize()
        return ConsoleGeometry(size.width, size.height)
    }

    override suspend fun ansiLevel(): ConsoleAnsiLevel {
        val t = terminal ?: return ConsoleAnsiLevel.NONE
        return when (t.terminalInfo.ansiLevel) {
            AnsiLevel.NONE -> ConsoleAnsiLevel.NONE
            AnsiLevel.ANSI16 -> ConsoleAnsiLevel.BASIC16
            AnsiLevel.ANSI256 -> ConsoleAnsiLevel.ANSI256
            AnsiLevel.TRUECOLOR -> ConsoleAnsiLevel.TRUECOLOR
        }
    }

    override suspend fun write(text: String) {
        terminal?.rawPrint(text)
    }

    override suspend fun flush() {
        // Mordant prints via platform streams immediately.
    }

    override fun events(): ConsoleEventSource {
        val t = terminal ?: return object : ConsoleEventSource {
            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? = null

            override suspend fun close() {}
        }
        val out = Channel<ConsoleEvent>(Channel.UNLIMITED)
        val sourceState = Mutex()
        var running = true

        globalLaunch {
        var lastWidth = t.updateSize().width
        var lastHeight = t.updateSize().height
        val startMark = TimeSource.Monotonic.markNow()
        var lastHeartbeatMark = startMark
        var loops = 0L
        var readAttempts = 0L
        var readFailures = 0L
        var keyEvents = 0L
        var resizeEvents = 0L
        var rawNullLoops = 0L
        var lastKeyMark = startMark
        var lastRawRecoveryMark = startMark

        consoleFlowDebug("events: collector started")
        try {
            while (currentCoroutineContext().isActive && sourceState.withLock { running }) {
                loops += 1
                val currentSize = runCatching { t.updateSize() }.getOrNull()
                if (currentSize == null) {
                    delay(150)
                    continue
                }
                if (currentSize.width != lastWidth || currentSize.height != lastHeight) {
                    out.trySend(ConsoleEvent.Resize(currentSize.width, currentSize.height))
                    lastWidth = currentSize.width
                    lastHeight = currentSize.height
                    resizeEvents += 1
                }

                val raw = stateMutex.withLock {
                    if (!rawModeRequested) {
                        null
                    } else {
                        // Recover raw scope lazily if it was dropped due to an earlier read failure.
                        if (rawModeScope == null) {
                            rawModeScope = t.enterRawModeOrNull()
                            if (rawModeScope == null) {
                                consoleFlowDebug("events: failed to reopen raw mode scope")
                            } else {
                                consoleFlowDebug("events: raw mode scope reopened")
                            }
                        }
                        rawModeScope
                    }
                }
                if (raw == null || !t.terminalInfo.inputInteractive) {
                    rawNullLoops += 1
                    delay(150)
                    if (lastHeartbeatMark.elapsedNow() >= 2.seconds) {
                        consoleFlowDebug(
                            "events: heartbeat loops=$loops reads=$readAttempts readFailures=$readFailures keys=$keyEvents resize=$resizeEvents rawNullLoops=$rawNullLoops rawRequested=$rawModeRequested inputInteractive=${t.terminalInfo.inputInteractive}"
                        )
                        lastHeartbeatMark = TimeSource.Monotonic.markNow()
                    }
                    continue
                }

                readAttempts += 1
                val readResult = runCatching { raw.readEventOrNull(150.milliseconds) }
                if (readResult.isFailure) {
                    readFailures += 1
                    consoleFlowDebug("events: readEventOrNull failed; resetting raw scope", readResult.exceptionOrNull())
                    // Raw scope became invalid; close and force reopen on next iteration.
                    stateMutex.withLock {
                        runCatching { rawModeScope?.close() }
                        rawModeScope = null
                    }
                    delay(50)
                    continue
                }
                val ev = readResult.getOrNull()

                val resized = runCatching { t.updateSize() }.getOrNull()
                if (resized != null && (resized.width != lastWidth || resized.height != lastHeight)) {
                    out.trySend(ConsoleEvent.Resize(resized.width, resized.height))
                    lastWidth = resized.width
                    lastHeight = resized.height
                }

                when (ev) {
                    is KeyboardEvent -> {
                        keyEvents += 1
                        lastKeyMark = TimeSource.Monotonic.markNow()
                        out.trySend(
                            ConsoleEvent.KeyDown(
                                key = ev.key,
                                code = null,
                                ctrl = ev.ctrl,
                                alt = ev.alt,
                                shift = ev.shift,
                                meta = false,
                            )
                        )
                    }

                    else -> {
                        // Mouse/other events are ignored in Lyng console v1.
                    }
                }

                // Some terminals silently stop delivering keyboard events while raw reads keep succeeding.
                // If we had keys before and then prolonged key inactivity, proactively recycle raw scope.
                if (keyEvents > 0L &&
                    lastKeyMark.elapsedNow() >= 4.seconds &&
                    lastRawRecoveryMark.elapsedNow() >= 4.seconds
                ) {
                    if (rawModeRequested) {
                        consoleFlowDebug("events: key inactivity detected; forcing raw reset")
                        val resetOk = forceRawModeReset(t)
                        if (resetOk) {
                            consoleFlowDebug("events: raw reset succeeded during inactivity recovery")
                            lastKeyMark = TimeSource.Monotonic.markNow()
                        } else {
                            consoleFlowDebug("events: raw reset failed during inactivity recovery")
                        }
                        lastRawRecoveryMark = TimeSource.Monotonic.markNow()
                    }
                }

                if (lastHeartbeatMark.elapsedNow() >= 2.seconds) {
                    consoleFlowDebug(
                        "events: heartbeat loops=$loops reads=$readAttempts readFailures=$readFailures keys=$keyEvents resize=$resizeEvents rawNullLoops=$rawNullLoops rawRequested=$rawModeRequested inputInteractive=${t.terminalInfo.inputInteractive}"
                    )
                    lastHeartbeatMark = TimeSource.Monotonic.markNow()
                }
            }
        } catch (e: CancellationException) {
            consoleFlowDebug("events: collector cancelled (normal)")
            // normal
        } catch (e: ScriptFlowIsNoMoreCollected) {
            consoleFlowDebug("events: collector stopped by flow consumer (normal)")
            // normal
        } catch (e: Exception) {
            consoleFlowDebug("events: collector loop failed", e)
            // terminate event source loop
        } finally {
            consoleFlowDebug(
                "events: collector ended uptime=${startMark.elapsedNow().inWholeMilliseconds}ms loops=$loops reads=$readAttempts readFailures=$readFailures keys=$keyEvents resize=$resizeEvents rawNullLoops=$rawNullLoops rawRequested=$rawModeRequested"
            )
            out.close()
        }
        }

        return object : ConsoleEventSource {
            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? {
                if (timeoutMs <= 0L) {
                    return out.receiveCatching().getOrNull()
                }
                return withTimeoutOrNull(timeoutMs.milliseconds) {
                    out.receiveCatching().getOrNull()
                }
            }

            override suspend fun close() {
                sourceState.withLock { running = false }
                out.close()
            }
        }
    }

    override suspend fun setRawMode(enabled: Boolean): Boolean {
        val t = terminal ?: return false
        return stateMutex.withLock {
            if (enabled) {
                if (!t.terminalInfo.inputInteractive) return@withLock false
                if (rawModeRequested) return@withLock false
                val scope = t.enterRawModeOrNull() ?: return@withLock false
                rawModeScope = scope
                rawModeRequested = true
                consoleFlowDebug("setRawMode(true): enabled")
                true
            } else {
                val hadRaw = rawModeRequested || rawModeScope != null
                rawModeRequested = false
                val scope = rawModeScope
                rawModeScope = null
                runCatching { scope?.close() }
                    .onFailure { consoleFlowDebug("setRawMode(false): close failed", it) }
                consoleFlowDebug("setRawMode(false): disabled hadRaw=$hadRaw")
                hadRaw
            }
        }
    }
}
