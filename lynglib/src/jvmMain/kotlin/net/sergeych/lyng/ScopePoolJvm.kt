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
 * JVM actual: per-thread scope frame pool backed by ThreadLocal.
 * Used only when [PerfFlags.SCOPE_POOL] is true.
 */
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val threadLocalPool: ThreadLocal<ArrayDeque<Scope>> = ThreadLocal.withInitial {
        ArrayDeque<Scope>(MAX_POOL_SIZE)
    }

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val pool = threadLocalPool.get()
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        // Always reset state on borrow to guarantee fresh-frame semantics
        s.resetForReuse(parent, args, pos, thisObj)
        return s
    }

    actual fun release(scope: Scope) {
        val pool = threadLocalPool.get()
        // Scrub sensitive references to avoid accidental retention
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
