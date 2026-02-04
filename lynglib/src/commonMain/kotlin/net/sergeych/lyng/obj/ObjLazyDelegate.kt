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

package net.sergeych.lyng.obj

import net.sergeych.lyng.*

/**
 * Lazy delegate used by `val x by lazy { ... }`.
 */
class ObjLazyDelegate(
    private val builder: Statement,
    private val capturedScope: Scope,
) : Obj() {
    override val objClass: ObjClass = type

    private var calculated = false
    private var cachedValue: Obj = ObjVoid

    override suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?,
    ): Obj {
        return when (name) {
            "getValue" -> {
                if (!calculated) {
                    val callScope = capturedScope.createChildScope(capturedScope.pos, args = Arguments.EMPTY)
                    cachedValue = builder.callOn(callScope)
                    calculated = true
                }
                cachedValue
            }
            "setValue" -> scope.raiseIllegalAssignment("lazy delegate is read-only")
            else -> super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
        }
    }

    companion object {
        val type = ObjClass("LazyDelegate").apply {
            implementingNames.add("Delegate")
            createField(
                "getValue",
                ObjNull,
                isMutable = false,
                visibility = Visibility.Public,
                pos = Pos.builtIn,
                declaringClass = this,
                type = ObjRecord.Type.Fun
            )
            createField(
                "setValue",
                ObjNull,
                isMutable = false,
                visibility = Visibility.Public,
                pos = Pos.builtIn,
                declaringClass = this,
                type = ObjRecord.Type.Fun
            )
        }
    }
}
