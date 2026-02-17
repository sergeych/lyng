/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng.obj

import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeBridge
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.Statement

class ObjExternCallable private constructor(
    private val target: Obj?,
    private val fn: (suspend ScopeFacade.() -> Obj)?
) : Obj() {

    override val objClass: ObjClass
        get() = Statement.type

    override suspend fun callOn(scope: Scope): Obj {
        val facade = ScopeBridge(scope)
        return when {
            fn != null -> facade.fn()
            target != null -> target.callOn(scope)
            else -> ObjVoid
        }
    }

    override fun toString(): String = "ExternCallable@${hashCode()}"

    companion object {
        fun wrap(target: Obj): ObjExternCallable = ObjExternCallable(target, null)
        fun fromBridge(fn: suspend ScopeFacade.() -> Obj): ObjExternCallable = ObjExternCallable(null, fn)
    }
}
