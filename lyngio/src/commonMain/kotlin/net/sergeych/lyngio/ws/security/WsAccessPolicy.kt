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

package net.sergeych.lyngio.ws.security

import net.sergeych.lyngio.fs.security.AccessContext
import net.sergeych.lyngio.fs.security.AccessDecision
import net.sergeych.lyngio.fs.security.Decision

sealed interface WsAccessOp {
    data class Connect(val url: String) : WsAccessOp
    data class Send(val url: String, val bytes: Int, val isText: Boolean) : WsAccessOp
    data class Receive(val url: String) : WsAccessOp
}

class WsAccessDeniedException(
    val op: WsAccessOp,
    val reasonDetail: String? = null,
) : IllegalStateException("WebSocket access denied for $op" + (reasonDetail?.let { ": $it" } ?: ""))

interface WsAccessPolicy {
    suspend fun check(op: WsAccessOp, ctx: AccessContext = AccessContext()): AccessDecision

    suspend fun require(op: WsAccessOp, ctx: AccessContext = AccessContext()) {
        val res = check(op, ctx)
        if (!res.isAllowed()) throw WsAccessDeniedException(op, res.reason)
    }
}

object PermitAllWsAccessPolicy : WsAccessPolicy {
    override suspend fun check(op: WsAccessOp, ctx: AccessContext): AccessDecision =
        AccessDecision(Decision.Allow)
}
