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

package net.sergeych.lyng.obj

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Statement

class ObjMutex(val mutex: Mutex): Obj() {
    override val objClass = type

    companion object {
        val type = object: ObjClass("Mutex") {
            override suspend fun callOn(scope: Scope): Obj {
                return ObjMutex(Mutex())
            }
        }.apply {
            addFn("withLock") {
                val f = requiredArg<Statement>(0)
                // Execute user lambda directly in the current scope to preserve the active scope
                // ancestry across suspension points. The lambda still constructs a ClosureScope
                // on top of this frame, and parseLambdaExpression sets skipScopeCreation for its body.
                thisAs<ObjMutex>().mutex.withLock { f.execute(this) }
            }
        }
    }
}