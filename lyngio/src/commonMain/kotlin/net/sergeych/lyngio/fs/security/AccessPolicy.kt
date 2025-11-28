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

package net.sergeych.lyngio.fs.security

import net.sergeych.lyngio.fs.LyngPath

/**
 * Primitive filesystem operations for access control decisions.
 * Keep this sealed hierarchy minimal and extensible.
 */
sealed interface AccessOp {
    data class ListDir(val path: LyngPath) : AccessOp
    data class CreateFile(val path: LyngPath) : AccessOp
    data class OpenRead(val path: LyngPath) : AccessOp
    data class OpenWrite(val path: LyngPath) : AccessOp
    data class OpenAppend(val path: LyngPath) : AccessOp
    data class Delete(val path: LyngPath) : AccessOp
    data class Rename(val from: LyngPath, val to: LyngPath) : AccessOp

    /** Update file metadata/attributes (times, permissions, flags, etc.). */
    data class UpdateAttributes(val path: LyngPath) : AccessOp
}

/**
 * Optional contextual information for access decisions (principal, tags, attributes).
 * This is intentionally generic to avoid coupling with specific auth models.
 */
data class AccessContext(
    val principal: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
)

enum class Decision { Allow, Deny }

data class AccessDecision(
    val decision: Decision,
    val reason: String? = null,
) {
    fun isAllowed(): Boolean = decision == Decision.Allow
}

class AccessDeniedException(
    val op: AccessOp,
    val reasonDetail: String? = null,
) : IllegalStateException("Access denied for $op" + (reasonDetail?.let { ": $it" } ?: ""))

/**
 * Policy interface that decides whether a specific filesystem operation is allowed.
 *
 * Note: by convention, updating file attributes should be treated as equivalent to
 * write permission unless a stricter policy is desired. I.e., policies may implement
 * [AccessOp.UpdateAttributes] by delegating to [AccessOp.OpenWrite] for the same path.
 */
interface FsAccessPolicy {
    suspend fun check(op: AccessOp, ctx: AccessContext = AccessContext()): AccessDecision

    // Convenience helpers
    suspend fun require(op: AccessOp, ctx: AccessContext = AccessContext()) {
        val res = check(op, ctx)
        if (!res.isAllowed()) throw AccessDeniedException(op, res.reason)
    }

    suspend fun canList(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.ListDir(path), ctx).isAllowed()

    suspend fun canCreateFile(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.CreateFile(path), ctx).isAllowed()

    suspend fun canOpenRead(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.OpenRead(path), ctx).isAllowed()

    suspend fun canOpenWrite(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.OpenWrite(path), ctx).isAllowed()

    suspend fun canOpenAppend(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.OpenAppend(path), ctx).isAllowed()

    suspend fun canDelete(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.Delete(path), ctx).isAllowed()

    suspend fun canRename(from: LyngPath, to: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.Rename(from, to), ctx).isAllowed()

    /**
     * Updating file attributes defaults to the same access level as opening for write
     * in typical policies. Policies may override that to be stricter/looser.
     */
    suspend fun canUpdateAttributes(path: LyngPath, ctx: AccessContext = AccessContext()) =
        check(AccessOp.UpdateAttributes(path), ctx).isAllowed()
}
