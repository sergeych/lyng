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

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import net.sergeych.lyng.Scope

class ObjMutableBuffer(byteArray: UByteArray) : ObjBuffer(byteArray) {

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        byteArray[checkIndex(scope, index.toObj())] = when (newValue) {
            is ObjInt -> newValue.value.toUByte()
            is ObjChar -> newValue.value.code.toUByte()
            else -> scope.raiseIllegalArgument(
                "invalid byte value for buffer at index ${index.inspect(scope)}: ${newValue.inspect(scope)}"
            )
        }
    }

    companion object {

        private suspend fun createBufferFrom(scope: Scope, obj: Obj): ObjBuffer =
            when (obj) {
                is ObjBuffer -> ObjMutableBuffer(obj.byteArray.copyOf())
                is ObjInt -> {
                    if (obj.value < 0)
                        scope.raiseIllegalArgument("buffer size must be positive")
                    val data = UByteArray(obj.value.toInt())
                    ObjMutableBuffer(data)
                }

                is ObjString -> ObjMutableBuffer(obj.value.encodeToByteArray().asUByteArray())
                else -> {
                    if (obj.isInstanceOf(ObjIterable)) {
                        ObjMutableBuffer(
                            obj.toFlow(scope).map { it.toLong().toUByte() }.toList().toTypedArray()
                                .toUByteArray()
                        )
                    } else
                        scope.raiseIllegalArgument(
                            "can't construct buffer from ${obj.inspect(scope)}"
                        )
                }
            }

        val type = object : ObjClass("MutableBuffer", ObjBuffer.type) {
            override suspend fun callOn(scope: Scope): Obj {
                val args = scope.args.list
                return when (args.size) {
                    // empty buffer
                    0 -> ObjMutableBuffer(ubyteArrayOf())
                    1 -> createBufferFrom(scope, args[0])
                    else -> {
                        // create buffer from array, each argument should be a byte then:
                        val data = UByteArray(args.size)
                        for ((i, b) in args.withIndex()) {
                            val code = when (b) {
                                is ObjChar -> b.value.code.toUByte()
                                is ObjInt -> b.value.toUByte()
                                else -> scope.raiseIllegalArgument(
                                    "invalid byte value for buffer constructor at index $i: ${b.inspect(scope)}"
                                )
                            }
                            data[i] = code
                        }
                        ObjMutableBuffer(data)
                    }
                }
            }
        }
    }
}
