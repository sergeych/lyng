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

package net.sergeych.lyngio.net.security

import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision

sealed interface NetAccessOp {
    data class Resolve(val host: String, val port: Int) : NetAccessOp
    data class TcpConnect(val host: String, val port: Int) : NetAccessOp
    data class TcpListen(val host: String?, val port: Int, val backlog: Int) : NetAccessOp
    data class UdpBind(val host: String?, val port: Int) : NetAccessOp
}

class NetAccessDeniedException(
    val op: NetAccessOp,
    val reasonDetail: String? = null,
) : IllegalStateException("Network access denied for $op" + (reasonDetail?.let { ": $it" } ?: ""))

interface NetAccessPolicy {
    suspend fun check(op: NetAccessOp, ctx: AccessContext = AccessContext()): AccessDecision

    suspend fun require(op: NetAccessOp, ctx: AccessContext = AccessContext()) {
        val res = check(op, ctx)
        if (!res.isAllowed()) throw NetAccessDeniedException(op, res.reason)
    }
}

object PermitAllNetAccessPolicy : NetAccessPolicy {
    override suspend fun check(op: NetAccessOp, ctx: AccessContext): AccessDecision =
        AccessDecision(Decision.Allow)
}
