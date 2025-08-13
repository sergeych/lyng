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

import net.sergeych.lyng.Scope

class ObjArrayIterator(val array: Obj) : Obj() {

    override val objClass: ObjClass by lazy { type }

    private var nextIndex = 0
    private var lastIndex = 0

    suspend fun init(scope: Scope) {
        nextIndex = 0
        lastIndex = array.invokeInstanceMethod(scope, "size").toInt()
        ObjVoid
    }

    companion object {
        val type by lazy {
            ObjClass("ArrayIterator", ObjIterator).apply {
                addFn("next") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex < self.lastIndex) {
                        self.array.invokeInstanceMethod(this, "getAt", (self.nextIndex++).toObj())
                    } else raiseError(ObjIterationFinishedException(this))
                }
                addFn("hasNext") {
                    val self = thisAs<ObjArrayIterator>()
                    if (self.nextIndex < self.lastIndex) ObjTrue else ObjFalse
                }
            }
        }
    }
}