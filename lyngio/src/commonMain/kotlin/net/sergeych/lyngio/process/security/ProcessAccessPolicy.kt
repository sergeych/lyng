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

package net.sergeych.lyngio.process.security

import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision

/**
 * Primitive process operations for access control decisions.
 */
sealed interface ProcessAccessOp {
    data class Execute(val executable: String, val args: List<String>) : ProcessAccessOp
    data class Shell(val command: String) : ProcessAccessOp
}

class ProcessAccessDeniedException(
    val op: ProcessAccessOp,
    val reasonDetail: String? = null,
) : IllegalStateException("Process access denied for $op" + (reasonDetail?.let { ": $it" } ?: ""))

/**
 * Policy interface that decides whether a specific process operation is allowed.
 */
interface ProcessAccessPolicy {
    suspend fun check(op: ProcessAccessOp, ctx: AccessContext = AccessContext()): AccessDecision

    // Convenience helpers
    suspend fun require(op: ProcessAccessOp, ctx: AccessContext = AccessContext()) {
        val res = check(op, ctx)
        if (!res.isAllowed()) throw ProcessAccessDeniedException(op, res.reason)
    }

    suspend fun canExecute(executable: String, args: List<String>, ctx: AccessContext = AccessContext()) =
        check(ProcessAccessOp.Execute(executable, args), ctx).isAllowed()

    suspend fun canShell(command: String, ctx: AccessContext = AccessContext()) =
        check(ProcessAccessOp.Shell(command), ctx).isAllowed()
}

object PermitAllProcessAccessPolicy : ProcessAccessPolicy {
    override suspend fun check(op: ProcessAccessOp, ctx: AccessContext): AccessDecision =
        AccessDecision(Decision.Allow)
}
