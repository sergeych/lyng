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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj

/**
 * Native actual: per-thread scope frame pool using @ThreadLocal.
 */
@kotlin.native.concurrent.ThreadLocal
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val pool = ArrayDeque<Scope>(MAX_POOL_SIZE)

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        if (pool.isNotEmpty()) {
            val s = pool.removeLast()
            try {
                // Re-initialize pooled instance
                s.resetForReuse(parent, args, pos, thisObj)
                return s
            } catch (e: IllegalStateException) {
                // Defensive fallback: if a cycle in scope parent chain is detected during reuse,
                // discard pooled instance for this call frame and allocate a fresh scope instead.
                if (e.message?.contains("cycle") == true && e.message?.contains("scope parent chain") == true) {
                    return Scope(parent, args, pos, thisObj)
                } else {
                    throw e
                }
            }
        }
        return Scope(parent, args, pos, thisObj)
    }

    actual fun release(scope: Scope) {
        if (pool.size < MAX_POOL_SIZE) {
            // Scrub sensitive references to avoid accidental retention before returning to pool
            scope.scrub()
            pool.addLast(scope)
        }
    }
}
