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

package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

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
        addClassFn("encode") { scp ->
            encodeAny(scp, scp.requireOnlyArg<Obj>())
        }
        addClassFn("decode") { scp ->
            decodeAny(scp, scp.requireOnlyArg<Obj>())
        }
    }
}

/**
 * Encode any object into Lynon format. Note that it has a special
 * handling for void values, returning an empty byte array.
 *
 * This is the default behavior for encoding void values in Lynon format,
 * ensuring consistency with decoding behavior. It matches the [lynonDecodeAny]
 * behavior for handling void values.
 */
@Suppress("unused")
suspend fun lynonEncodeAny(scope: Scope, value: Obj): UByteArray =
    if (value == ObjVoid)
        ubyteArrayOf()
    else
        (ObjLynonClass.encodeAny(scope, value))
            .bitArray.asUByteArray()


/**
 * Decode any object from Lynon format. If the input is empty, returns ObjVoid.
 * This behavior is designed to handle cases where the input data might be incomplete
 * or intentionally left empty, indicating a void or null value and matches
 * the [lynonEncodeAny] behavior [ObjVoid].
 */
@Suppress("unused")
suspend fun lynonDecodeAny(scope: Scope, encoded: UByteArray): Obj =
    if (encoded.isEmpty())
        ObjVoid
    else
        ObjLynonClass.decodeAny(
        scope,
        ObjBitBuffer(
            BitArray(encoded, 8)
        )
    )
