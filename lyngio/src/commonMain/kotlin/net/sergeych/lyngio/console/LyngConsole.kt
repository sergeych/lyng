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

/**
 * ANSI color support level detected for the active console.
 */
enum class ConsoleAnsiLevel {
    NONE,
    BASIC16,
    ANSI256,
    TRUECOLOR,
}

/**
 * Console geometry in character cells.
 */
data class ConsoleGeometry(
    val columns: Int,
    val rows: Int,
)

/**
 * Input/terminal events emitted by the console runtime.
 */
sealed interface ConsoleEvent {
    data class Resize(
        val columns: Int,
        val rows: Int,
    ) : ConsoleEvent

    data class KeyDown(
        val key: String,
        val code: String? = null,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val meta: Boolean = false,
    ) : ConsoleEvent

    data class KeyUp(
        val key: String,
        val code: String? = null,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val meta: Boolean = false,
    ) : ConsoleEvent
}

/**
 * Pull-based console event source.
 *
 * `nextEvent(timeoutMs)` returns:
 * - next event when available,
 * - `null` on timeout,
 * - `null` after close.
 */
interface ConsoleEventSource {
    suspend fun nextEvent(timeoutMs: Long = 0L): ConsoleEvent?

    suspend fun close()
}

private object EmptyConsoleEventSource : ConsoleEventSource {
    override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? = null

    override suspend fun close() {
        // no-op
    }
}

/**
 * Platform-independent console runtime surface.
 */
interface LyngConsole {
    val isSupported: Boolean

    suspend fun isTty(): Boolean

    suspend fun geometry(): ConsoleGeometry?

    suspend fun ansiLevel(): ConsoleAnsiLevel

    suspend fun write(text: String)

    suspend fun flush()

    fun events(): ConsoleEventSource

    /**
     * Set terminal raw input mode. Returns true when mode was changed.
     */
    suspend fun setRawMode(enabled: Boolean): Boolean
}

object UnsupportedLyngConsole : LyngConsole {
    override val isSupported: Boolean = false

    override suspend fun isTty(): Boolean = false

    override suspend fun geometry(): ConsoleGeometry? = null

    override suspend fun ansiLevel(): ConsoleAnsiLevel = ConsoleAnsiLevel.NONE

    override suspend fun write(text: String) {
        // no-op
    }

    override suspend fun flush() {
        // no-op
    }

    override fun events(): ConsoleEventSource = EmptyConsoleEventSource

    override suspend fun setRawMode(enabled: Boolean): Boolean = false
}
