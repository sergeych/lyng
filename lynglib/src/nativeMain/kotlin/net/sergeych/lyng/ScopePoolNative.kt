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
 * Native actual: simple global deque pool. Many native targets are single-threaded by default in our setup.
 */
actual object ScopePool {
    private const val MAX_POOL_SIZE = 64
    private val pool = ArrayDeque<Scope>(MAX_POOL_SIZE)

    actual fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope {
        val s = if (pool.isNotEmpty()) pool.removeLast() else Scope(parent, args, pos, thisObj)
        if (s.parent !== parent || s.args !== args || s.pos !== pos || s.thisObj !== thisObj) {
            s.resetForReuse(parent, args, pos, thisObj)
        } else {
            s.frameId = nextFrameId()
        }
        return s
    }

    actual fun release(scope: Scope) {
        scope.resetForReuse(parent = null, args = Arguments.EMPTY, pos = Pos.builtIn, thisObj = ObjVoid)
        if (pool.size < MAX_POOL_SIZE) pool.addLast(scope)
    }
}
