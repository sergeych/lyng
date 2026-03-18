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

import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision

/**
 * Primitive console operations for access control decisions.
 */
sealed interface ConsoleAccessOp {
    data class WriteText(val length: Int) : ConsoleAccessOp

    data object ReadEvents : ConsoleAccessOp

    data class SetRawMode(val enabled: Boolean) : ConsoleAccessOp
}

class ConsoleAccessDeniedException(
    val op: ConsoleAccessOp,
    val reasonDetail: String? = null,
) : IllegalStateException("Console access denied for $op" + (reasonDetail?.let { ": $it" } ?: ""))

/**
 * Policy interface that decides whether a specific console operation is allowed.
 */
interface ConsoleAccessPolicy {
    suspend fun check(op: ConsoleAccessOp, ctx: AccessContext = AccessContext()): AccessDecision

    suspend fun require(op: ConsoleAccessOp, ctx: AccessContext = AccessContext()) {
        val res = check(op, ctx)
        if (!res.isAllowed()) throw ConsoleAccessDeniedException(op, res.reason)
    }
}

object PermitAllConsoleAccessPolicy : ConsoleAccessPolicy {
    override suspend fun check(op: ConsoleAccessOp, ctx: AccessContext): AccessDecision =
        AccessDecision(Decision.Allow)
}
