package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjInt

class LynonPacker(bout: MemoryBitOutput = MemoryBitOutput()) : LynonEncoder(bout) {
    fun toUByteArray(): UByteArray = (bout as MemoryBitOutput).toUByteArray()
}

class LynonUnpacker(source: UByteArray) : LynonDecoder(MemoryBitInput(source))

open class LynonEncoder(val bout: BitOutput) {

    fun shouldCache(obj: Obj): Boolean = when (obj) {
        is ObjChar -> false
        is ObjInt -> obj.value > 0x10000FF
        is ObjBool -> false
        else -> true
    }

    val cache = mutableMapOf<Any, Int>()

    inline fun encodeCached(item: Any, packer: LynonEncoder.() -> Unit) {
        if (item is Obj) {
            cache[item]?.let { cacheId ->
                val size = sizeInBits(cache.size)
                bout.putBit(1)
                bout.putBits(cacheId.toULong(), size)
            } ?: run {
                bout.putBit(0)
                if (shouldCache(item)) {
                    bout.putBit(1)
                    cache[item] = cache.size
                } else
                    bout.putBit(0)
                packer()
            }
        }
    }

    suspend fun encodeObj(scope: Scope, obj: Obj) {
        encodeCached(obj) {
            obj.serialize(scope, this)
        }
    }

    fun encodeBinaryData(data: ByteArray) {
        bout.packUnsigned(data.size.toULong())
        bout.putBytes(data)
    }

    fun encodeSigned(value: Long) {
        bout.packSigned(value)
    }

    @Suppress("unused")
    fun encodeUnsigned(value: ULong) {
        bout.packUnsigned(value)
    }

    @Suppress("unused")
    fun encodeBool(value: Boolean) {
        bout.putBit(if (value) 1 else 0)
    }

    fun encodeReal(value: Double) {
        bout.putBits(value.toRawBits().toULong(), 64)
    }

    fun encodeBoolean(value: Boolean) {
        bout.putBit(if (value) 1 else 0)
    }

}