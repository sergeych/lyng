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

import net.sergeych.bintools.toDump
import net.sergeych.lyng.Scope
import net.sergeych.lynon.BitArray

class ObjBitBuffer(val bitArray: BitArray) : Obj() {

    override val objClass = type

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return bitArray[index.toLong()].toObj()
    }

    companion object {
        val type = object: ObjClass("BitBuffer", ObjArray) {

        }.apply {
            addFn("toBuffer") {
                requireNoArgs()
                ObjBuffer(thisAs<ObjBitBuffer>().bitArray.asUbyteArray())
            }
            addFn("toDump") {
                requireNoArgs()
                ObjString(
                    thisAs<ObjBitBuffer>().bitArray.asUbyteArray().toDump()
                )
            }
            addFn("size") {
                thisAs<ObjBitBuffer>().bitArray.size.toObj()
            }
            addFn("sizeInBytes") {
                ObjInt((thisAs<ObjBitBuffer>().bitArray.size + 7) shr 3)
            }
        }
    }
}