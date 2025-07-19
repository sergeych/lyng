package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass

open class LynonDecoder(val bin: BitInput, val settings: LynonSettings = LynonSettings.default) {

    fun getBitsAsInt(bitsSize: Int): Int {
        return bin.getBits(bitsSize).toInt()
    }

    fun unpackUnsignedInt(): Int = bin.unpackUnsigned().toInt()

    fun decompress() = bin.decompress()

    val cache = mutableListOf<Any>()


    inline fun <T : Any>decodeCached(f: LynonDecoder.() -> T): T {
        return if (bin.getBit() == 0) {
            // unpack and cache
            f().also {
                if (settings.shouldCache(it)) cache.add(it)
            }
        } else {
            // get cache reference
            val size = sizeInBits(cache.size)
            val id = bin.getBitsOrNull(size)?.toInt()
                ?: throw RuntimeException("Invalid object id: unexpected end of stream")
            if (id >= cache.size) throw RuntimeException("Invalid object id: $id should be in 0..<${cache.size}")
            @Suppress("UNCHECKED_CAST")
            cache[id] as T
        }
    }

    suspend fun decodeAny(scope: Scope): Obj = decodeCached {
        val type = LynonType.entries[bin.getBits(4).toInt()]
        type.objClass.deserialize(scope, this, type)
    }

    suspend fun decodeObject(scope: Scope, type: ObjClass): Obj {
        return decodeCached {  type.deserialize(scope, this, null) }
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