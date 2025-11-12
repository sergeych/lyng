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

/**
 * Expect/actual portable scope frame pool. Used only when [PerfFlags.SCOPE_POOL] is true.
 * JVM actual provides a ThreadLocal-backed pool; other targets may use a simple global deque.
 */
expect object ScopePool {
    fun borrow(parent: Scope, args: Arguments, pos: Pos, thisObj: Obj): Scope
    fun release(scope: Scope)
}
