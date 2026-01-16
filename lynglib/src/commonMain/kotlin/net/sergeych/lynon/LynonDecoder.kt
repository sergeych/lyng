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
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjString

open class LynonDecoder(val bin: BitInput, val settings: LynonSettings = LynonSettings.default) {

    fun getBitsAsInt(bitsSize: Int): Int {
        return bin.getBits(bitsSize).toInt()
    }

    fun unpackUnsignedInt(): Int = bin.unpackUnsigned().toInt()

    fun decompress() = bin.decompress()

    val cache = mutableListOf<Any>()


    inline fun <reified T : Any> decodeCached(f: LynonDecoder.() -> T): T {
        return if (bin.getBit() == 0) {
            // unpack and cache
            f().also {
//                println("decode: cache miss: ${cache.size}: $it:${it::class.simpleName}")
                if (settings.shouldCache(it)) cache.add(it)
            }
        } else {
            // get cache reference
            val size = sizeInBits(cache.size)
            val id = bin.getBitsOrNull(size)?.toInt()
                ?: throw RuntimeException("Invalid object id: unexpected end of stream")
            if (id >= cache.size) throw RuntimeException("Invalid object id: $id should be in 0..<${cache.size}")
//            println("decode: cache hit ${id}: ${cache[id]}:${cache[id]::class.simpleName}")
//            @Suppress("UNCHECKED_CAST")
            cache[id] as T
        }
    }

    suspend fun decodeAny(scope: Scope): Obj = decodeCached {
        val type = LynonType.entries[bin.getBits(4).toInt()]
        if (type != LynonType.Other) {
            type.objClass.deserialize(scope, this, type)
        } else {
            decodeClassObj(scope).deserialize(scope, this, null)
        }
    }

    /**
     * Decode any object with [decodeAny] and cast it to [T] or raise Lyng's class cast error
     * with [Scope.raiseClassCastError].
     *
     * @return T typed Lyng object
     */
    suspend inline fun <reified T : Obj> decodeAnyAs(scope: Scope): T {
        val x = decodeAny(scope)
        return (x as? T) ?: scope.raiseClassCastError(
                "Expected ${T::class.simpleName} but got $x"
            )
    }

    private suspend fun decodeClassObj(scope: Scope): ObjClass {
        val className = decodeObject(scope, ObjString.type, null) as ObjString
        return scope.get(className.value)?.value?.let {
            if (it is ObjClass) return it
            if (it is ObjInstance && it.objClass.className == className.value) return it.objClass
            scope.raiseClassCastError("Expected obj class but got ${it::class.simpleName}")
        } ?: run {
            // Use Scope API that mirrors compiler-emitted ObjRef chain for qualified identifiers
            val evaluated = scope.resolveQualifiedIdentifier(className.value)
            if (evaluated is ObjClass) return evaluated
            if (evaluated is ObjInstance && evaluated.objClass.className == className.value) return evaluated.objClass
            scope.raiseClassCastError("Expected obj class but got ${evaluated::class.simpleName}")
            evaluated as ObjClass // unreachable but for compiler
        }
    }

    // helper moved to Scope as resolveQualifiedIdentifier

    suspend fun decodeAnyList(scope: Scope, fixedSize: Int? = null): MutableList<Obj> {
        return if (bin.getBit() == 1) {
            // homogenous
            val type = LynonType.entries[getBitsAsInt(4)]
            val list = mutableListOf<Obj>()
            val objClass = if (type == LynonType.Other)
                decodeClassObj(scope)//.also { println("detected class obj: $it") }
            else type.objClass
            val size = fixedSize ?: bin.unpackUnsigned().toInt()
//            println("detected homogenous list type $type, $size items")
            for (i in 0..<size) {
                list += decodeObject(scope, objClass, type)
                //.also {
//                    println("decoded: $it")
//                }
            }
            list
        } else {
            val size = fixedSize ?: unpackUnsigned().toInt()
            (0..<size).map { decodeAny(scope) }.toMutableList()
        }
    }

    suspend fun decodeObject(scope: Scope, type: ObjClass, overrideType: LynonType? = null): Obj {
        return decodeCached { type.deserialize(scope, this, overrideType) }
    }

    fun unpackBinaryData(): ByteArray = bin.decompress()

    @Suppress("unused")
    fun unpackBinaryDataOrNull(): ByteArray? = bin.decompressOrNull()

    fun unpackBoolean(): Boolean {
        return bin.getBit() == 1
    }

    fun unpackDouble(): Double {
        return Double.fromBits(bin.getBits(64).toLong())
    }

    fun unpackSigned(): Long {
        return bin.unpackSigned()
    }

    fun unpackUnsigned(): ULong {
        return bin.unpackUnsigned()
    }

}