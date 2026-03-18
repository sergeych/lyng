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

package net.sergeych.lyngio.console.security

import net.sergeych.lyngio.console.*
import net.sergeych.lyngio.fs.security.AccessContext

/**
 * Decorator that applies a [ConsoleAccessPolicy] to a delegate [LyngConsole].
 */
class LyngConsoleSecured(
    private val delegate: LyngConsole,
    private val policy: ConsoleAccessPolicy,
    private val ctx: AccessContext = AccessContext(),
) : LyngConsole {

    override val isSupported: Boolean
        get() = delegate.isSupported

    override suspend fun isTty(): Boolean = delegate.isTty()

    override suspend fun geometry(): ConsoleGeometry? = delegate.geometry()

    override suspend fun ansiLevel(): ConsoleAnsiLevel = delegate.ansiLevel()

    override suspend fun write(text: String) {
        policy.require(ConsoleAccessOp.WriteText(text.length), ctx)
        delegate.write(text)
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun events(): ConsoleEventSource {
        val source = delegate.events()
        return object : ConsoleEventSource {
            override suspend fun nextEvent(timeoutMs: Long): ConsoleEvent? {
                policy.require(ConsoleAccessOp.ReadEvents, ctx)
                return source.nextEvent(timeoutMs)
            }

            override suspend fun close() {
                source.close()
            }
        }
    }

    override suspend fun setRawMode(enabled: Boolean): Boolean {
        policy.require(ConsoleAccessOp.SetRawMode(enabled), ctx)
        return delegate.setRawMode(enabled)
    }
}
