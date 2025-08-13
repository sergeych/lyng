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

package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBitBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjString

// Most often used types:


object ObjLynonClass : ObjClass("Lynon") {

    suspend fun encodeAny(scope: Scope, obj: Obj): ObjBitBuffer {
        val bout = MemoryBitOutput()
        val serializer = LynonEncoder(bout)
        serializer.encodeAny(scope, obj)
        return ObjBitBuffer(bout.toBitArray())
    }

    suspend fun decodeAny(scope: Scope, source: Obj): Obj {
        if (source !is ObjBitBuffer) throw Exception("Invalid source: $source")
        val bin = source.bitArray.toInput()
        val deserializer = LynonDecoder(bin)
        return deserializer.decodeAny(scope)
    }

    init {
        addClassConst("test", ObjString("test_const"))
        addClassFn("encode") {
            encodeAny(this, requireOnlyArg<Obj>())
        }
        addClassFn("decode") {
            decodeAny(this, requireOnlyArg<Obj>())
        }
    }
}

@Suppress("unused")
suspend fun lynonEncodeAny(scope: Scope, value: Obj): UByteArray =
    (ObjLynonClass.encodeAny(scope, value))
        .bitArray.asUbyteArray()

@Suppress("unused")
suspend fun lynonDecodeAny(scope: Scope, encoded: UByteArray): Obj =
    ObjLynonClass.decodeAny(
        scope,
        ObjBitBuffer(
            BitArray(encoded, 8)
        )
    )
