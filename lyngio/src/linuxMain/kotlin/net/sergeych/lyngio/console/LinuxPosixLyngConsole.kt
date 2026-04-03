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

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*
import kotlin.time.TimeSource

internal actual fun getNativeSystemConsole(): LyngConsole = LinuxPosixLyngConsole

private const val RAW_IDLE_POLL_MS = 10L
private const val NON_RAW_IDLE_POLL_MS = 25L
private const val ESCAPE_FOLLOWUP_TIMEOUT_MS = 25L

internal object LinuxConsoleKeyDecoder {
    fun decode(firstCode: Int, nextCode: (Long) -> Int?): ConsoleEvent.KeyDown {
        if (firstCode == 27) {
            val next = nextCode(25L)
            if (next == null || next < 0) return key("Escape")
            if (next == '['.code || next == 'O'.code) {
                val sb = StringBuilder()
                sb.append(next.toChar())
                var i = 0
                while (i < 8) {
                    val c = nextCode(25L) ?: break
                    if (c < 0) break
                    sb.append(c.toChar())
                    if (c.toChar().isLetter() || c == '~'.code) break
                    i += 1
                }
                return keyFromAnsiSequence(sb.toString()) ?: key("Escape")
            }
            val base = decodePlain(next)
            return ConsoleEvent.KeyDown(
                key = base.key,
                code = base.code,
                ctrl = base.ctrl,
                alt = true,
                shift = base.shift,
                meta = false,
            )
        }
        return decodePlain(firstCode)
    }

    private fun decodePlain(code: Int): ConsoleEvent.KeyDown {
        if (code == 3) return ConsoleEvent.KeyDown(key = "c", ctrl = true)
        if (code == 9) return key("Tab")
        if (code == 10 || code == 13) return key("Enter")
        if (code == 32) return key(" ")
        if (code == 127 || code == 8) return key("Backspace")
        val c = code.toChar()
        return if (c in 'A'..'Z') {
            ConsoleEvent.KeyDown(key = c.toString(), shift = true)
        } else {
            key(c.toString())
        }
    }

    private fun keyFromAnsiSequence(seq: String): ConsoleEvent.KeyDown? {
        val letter = seq.lastOrNull() ?: return null
        val shift = seq.contains(";2")
        val alt = seq.contains(";3")
        val ctrl = seq.contains(";5")
        val key = when (letter) {
            'A' -> "ArrowUp"
            'B' -> "ArrowDown"
            'C' -> "ArrowRight"
            'D' -> "ArrowLeft"
            'H' -> "Home"
            'F' -> "End"
            else -> return null
        }
        return ConsoleEvent.KeyDown(key = key, ctrl = ctrl, alt = alt, shift = shift)
    }

    private fun key(name: String): ConsoleEvent.KeyDown = ConsoleEvent.KeyDown(key = name)
}

@OptIn(ExperimentalForeignApi::class)
object LinuxPosixLyngConsole : LyngConsole {
    private val stateMutex = Mutex()
    private var rawModeRequested = false
    private var savedAttrsBlob: ByteArray? = null

    override val isSupported: Boolean
        get() = isatty(STDIN_FILENO) == 1 && isatty(STDOUT_FILENO) == 1

    override suspend fun isTty(): Boolean = isSupported

    override suspend fun geometry(): ConsoleGeometry? = readGeometry()

    override suspend fun ansiLevel(): ConsoleAnsiLevel {
        val colorTerm = (getenv("COLORTERM")?.toKString() ?: "").lowercase()
        val term = (getenv("TERM")?.toKString() ?: "").lowercase()
        return when {
            colorTerm.contains("truecolor") || colorTerm.contains("24bit") -> ConsoleAnsiLevel.TRUECOLOR
            term.contains("256color") -> ConsoleAnsiLevel.ANSI256
            term.isNotBlank() && term != "dumb" -> ConsoleAnsiLevel.BASIC16
            else -> ConsoleAnsiLevel.NONE
        }
    }

    override suspend fun write(text: String) {
        kotlin.io.print(text)
    }

    override suspend fun flush() {
        fflush(null)
    }

    override fun events(): ConsoleEventSource {
        if (!isSupported) {
            return object : ConsoleEventSource {
                override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? = null
                override suspend fun close() {}
            }
        }

        consoleFlowDebug("linux-events: source created")
        return object : ConsoleEventSource {
            var closed = false
            var lastGeometry: ConsoleGeometry? = null

            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? {
                if (closed) return null
                val started = TimeSource.Monotonic.markNow()
                while (!closed) {
                    val g = readGeometry()
                    if (g != null && (lastGeometry == null || g.columns != lastGeometry?.columns || g.rows != lastGeometry?.rows)) {
                        lastGeometry = g
                        return ConsoleEvent.Resize(g.columns, g.rows)
                    }

                    val rawRequested = stateMutex.withLock { rawModeRequested }
                    if (rawRequested) {
                        val ev = readKeyEventNonBlocking()
                        if (ev != null) return ev
                    }

                    val elapsedMs = started.elapsedNow().inWholeMilliseconds
                    if (timeoutMs > 0L && elapsedMs >= timeoutMs) {
                        return null
                    }

                    val remainingMs = if (timeoutMs > 0L) timeoutMs - elapsedMs else Long.MAX_VALUE
                    val idleMs = if (rawRequested) RAW_IDLE_POLL_MS else NON_RAW_IDLE_POLL_MS
                    val sleepMs = if (timeoutMs > 0L) minOf(idleMs, remainingMs) else idleMs
                    if (sleepMs > 0L) delay(sleepMs)
                }
                return null
            }

            override suspend fun close() {
                closed = true
                consoleFlowDebug("linux-events: source closed")
            }
        }
    }

    override suspend fun setRawMode(enabled: Boolean): Boolean {
        if (!isSupported) return false
        return stateMutex.withLock {
            if (enabled) {
                if (rawModeRequested) return@withLock false
                memScoped {
                    val attrs = alloc<termios>()
                    if (tcgetattr(STDIN_FILENO, attrs.ptr) != 0) return@withLock false

                    val saved = ByteArray(sizeOf<termios>().toInt())
                    saved.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), attrs.ptr, sizeOf<termios>().convert())
                    }
                    savedAttrsBlob = saved

                    configureRawInput(attrs)
                    if (tcsetattr(STDIN_FILENO, TCSANOW, attrs.ptr) != 0) return@withLock false
                }
                rawModeRequested = true
                consoleFlowDebug("linux-events: setRawMode(true): enabled")
                true
            } else {
                val hadRaw = rawModeRequested
                rawModeRequested = false
                val saved = savedAttrsBlob
                if (saved != null) {
                    memScoped {
                        val attrs = alloc<termios>()
                        saved.usePinned { pinned ->
                            memcpy(attrs.ptr, pinned.addressOf(0), sizeOf<termios>().convert())
                        }
                        tcsetattr(STDIN_FILENO, TCSANOW, attrs.ptr)
                    }
                }
                consoleFlowDebug("linux-events: setRawMode(false): disabled hadRaw=$hadRaw")
                hadRaw
            }
        }
    }

    private fun readGeometry(): ConsoleGeometry? = memScoped {
        val ws = alloc<winsize>()
        if (ioctl(STDOUT_FILENO, TIOCGWINSZ.convert(), ws.ptr) != 0) return null
        val cols = ws.ws_col.toInt()
        val rows = ws.ws_row.toInt()
        if (cols <= 0 || rows <= 0) return null
        ConsoleGeometry(columns = cols, rows = rows)
    }

    private fun readByte(timeoutMs: Long): Int? = memScoped {
        val pfd = alloc<pollfd>()
        pfd.fd = STDIN_FILENO
        pfd.events = POLLIN.convert()
        pfd.revents = 0
        val ready = poll(pfd.ptr, 1.convert(), timeoutMs.toInt())
        if (ready <= 0) return null

        readReadyByte()
    }

    private fun readReadyByte(): Int? {
        val buf = ByteArray(1)
        val count = buf.usePinned { pinned ->
            read(STDIN_FILENO, pinned.addressOf(0), 1.convert())
        }
        if (count <= 0) {
            if (count < 0) {
                consoleFlowDebug("linux-events: stdin read returned $count errno=$errno")
            }
            return null
        }
        val b = buf[0].toInt()
        return if (b < 0) b + 256 else b
    }

    private fun readByteNow(): Int? = memScoped {
        val pfd = alloc<pollfd>()
        pfd.fd = STDIN_FILENO
        pfd.events = POLLIN.convert()
        pfd.revents = 0
        val ready = poll(pfd.ptr, 1.convert(), 0)
        if (ready <= 0) return null
        readReadyByte()
    }

    private fun readKeyEventNonBlocking(): ConsoleEvent.KeyDown? {
        val first = readByteNow() ?: return null
        return LinuxConsoleKeyDecoder.decode(first) { timeout ->
            readByte(minOf(timeout, ESCAPE_FOLLOWUP_TIMEOUT_MS))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun configureRawInput(attrs: termios) {
    attrs.c_iflag = attrs.c_iflag and BRKINT.convert<UInt>().inv()
    attrs.c_iflag = attrs.c_iflag and ICRNL.convert<UInt>().inv()
    attrs.c_iflag = attrs.c_iflag and INPCK.convert<UInt>().inv()
    attrs.c_iflag = attrs.c_iflag and ISTRIP.convert<UInt>().inv()
    attrs.c_iflag = attrs.c_iflag and IXON.convert<UInt>().inv()
    attrs.c_oflag = attrs.c_oflag and OPOST.convert<UInt>().inv()
    attrs.c_cflag = attrs.c_cflag or CS8.convert<UInt>()
    attrs.c_lflag = attrs.c_lflag and ECHO.convert<UInt>().inv()
    attrs.c_lflag = attrs.c_lflag and ICANON.convert<UInt>().inv()
    attrs.c_lflag = attrs.c_lflag and IEXTEN.convert<UInt>().inv()
    attrs.c_lflag = attrs.c_lflag and ISIG.convert<UInt>().inv()
    attrs.c_cc[VMIN] = 0u
    attrs.c_cc[VTIME] = 1u
}
