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

@file:Suppress("unused")

package net.sergeych.lyng.obj

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.sergeych.lyng.Scope
import net.sergeych.lyng.bridge.LyngClassBridge

/**
 * Iterator wrapper to allow Kotlin collections to be returned from Lyng objects;
 * each object is converted to a Lyng object.
 */
class ObjKotlinIterator(val iterator: Iterator<Any?>) : Obj() {

    override val objClass get() = type

    companion object {
        private var boundType: ObjClass? = null

        val type: ObjClass
            get() = boundType ?: error("KotlinIterator class is not bound; import lyng.stdlib first")

        internal fun bindTo(cls: ObjClass) {
            if (boundType != null) {
                if (boundType === cls) return
                return
            }
            boundType = cls
            LyngClassBridge.bind(cls) {
                addFun("next") { scope, self, _ ->
                    when (self) {
                        is ObjKotlinIterator -> self.iterator.next().toObj()
                        is ObjKotlinObjIterator -> self.iterator.next()
                        else -> scope.raiseClassCastError("Expected KotlinIterator, got ${self.objClass.className}")
                    }
                }
                addFun("hasNext") { scope, self, _ ->
                    when (self) {
                        is ObjKotlinIterator -> self.iterator.hasNext().toObj()
                        is ObjKotlinObjIterator -> self.iterator.hasNext().toObj()
                        else -> scope.raiseClassCastError("Expected KotlinIterator, got ${self.objClass.className}")
                    }
                }
            }
        }

    }
}

/**
 * Propagate kotlin iterator that already produces Lyng objects, no conversion
 * is applied
 */
class ObjKotlinObjIterator(val iterator: Iterator<Obj>) : Obj() {

    override val objClass get() = type

    companion object {
        val type: ObjClass
            get() = ObjKotlinIterator.type

    }
}

/**
 * Convert Lyng's Iterable to Kotlin's Flow.
 *
 * As Lyng is totally asynchronous, its iterator can't be trivially converted to Kotlin's synchronous iterator.
 * It is, though, trivially convertible to Kotlin's Flow.
 */
fun Obj.toFlow(scope: Scope): Flow<Obj> = flow {
    val iterator = invokeInstanceMethod(scope, "iterator")
    val hasNext = iterator.getInstanceMethod(scope, "hasNext")
    val next = iterator.getInstanceMethod(scope, "next")
    while (hasNext.invoke(scope, iterator).toBool()) {
        emit(next.invoke(scope, iterator))
    }
}
