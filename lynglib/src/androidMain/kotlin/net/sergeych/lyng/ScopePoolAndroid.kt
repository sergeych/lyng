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

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjVoid

/**
 * Android actual: per-thread scope frame pool backed by ThreadLocal.
 */
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val threadLocalPool: ThreadLocal<ArrayDeque<Scope>?> = ThreadLocal()

    private fun pool(): ArrayDeque<Scope> {
        var p = threadLocalPool.get()
        if (p == null) {
            p = ArrayDeque<Scope>(MAX_POOL_SIZE)
            threadLocalPool.set(p)
        }
        return p
    }

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val pool = pool()
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        return try {
            if (s.parent !== parent || s.args !== args || s.pos !== pos || s.thisObj !== thisObj) {
                s.resetForReuse(parent, args, pos, thisObj)
            } else {
                s.frameId = nextFrameId()
            }
            s
        } catch (e: IllegalStateException) {
            if (e.message?.contains("cycle") == true && e.message?.contains("scope parent chain") == true) {
                Scope(parent, args, pos, thisObj)
            } else throw e
        }
    }

    actual fun release(scope: Scope) {
        val pool = pool()
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
