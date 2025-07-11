package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass

open class LynonDecoder(private val bin: BitInput) {

    val cache = mutableListOf<Obj>()

    fun unpackObject(scope: Scope, type: ObjClass): Obj {
        return if( bin.getBit() == 0 ) {
            // unpack and cache
            val cached = bin.getBool()
            type.deserialize(scope, this).also {
                if( cached ) cache.add(it)
            }
        }
        else {
            // get cache reference
            val size = sizeInBits(cache.size)
            val id = bin.getBitsOrNull(size)?.toInt() ?: scope.raiseError("Invalid object id: unexpected end of stream")
            if( id >= cache.size ) scope.raiseError("Invalid object id: $id should be in 0..<${cache.size}")
            cache[id]
        }
    }

    fun unpackBinaryData(): ByteArray? {
        val size = bin.unpackUnsigned()
        return bin.getBytes(size.toInt())
    }

    fun unpackBoolean(): Boolean {
        return bin.getBit() == 1
    }

    fun unpackDouble(): Double {
        return Double.fromBits(bin.getBits(64).toLong())
    }

    fun unpackSigned(): Long {
        return bin.unpackSigned()
    }

}