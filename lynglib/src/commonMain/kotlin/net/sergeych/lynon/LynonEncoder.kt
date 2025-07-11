package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjChar
import net.sergeych.lyng.obj.ObjInt

class LynonPacker(private val bout: MemoryBitOutput= MemoryBitOutput()) : LynonEncoder(bout) {
    fun toUByteArray(): UByteArray = bout.toUByteArray()
}

class LynonUnpacker(source: UByteArray) : LynonDecoder(MemoryBitInput(source))

open class LynonEncoder(private val bout: BitOutput) {

    fun shouldCache(obj: Obj): Boolean = when (obj) {
        is ObjChar -> false
        is ObjInt -> obj.value > 0x10000FF
        is ObjBool -> false
        else -> true
    }

    val cache = mutableMapOf<Obj,Int>()

    suspend fun packObject(scope: Scope,obj: Obj) {
        cache[obj]?.let { cacheId ->
            val size = sizeInBits(cache.size)
            bout.putBit(1)
            bout.putBits(cacheId, size)
        } ?: run {
            bout.putBit(0)
            if( shouldCache(obj) ) {
                bout.putBit(1)
                cache[obj] = cache.size
            }
            else
                bout.putBit(0)
            obj.serialize(scope, this)
        }
    }

    fun packBinaryData(data: ByteArray) {
        bout.packUnsigned(data.size.toULong())
        bout.putBytes(data)
    }

    fun packSigned(value: Long) { bout.packSigned(value) }
    @Suppress("unused")
    fun packUnsigned(value: ULong) { bout.packUnsigned(value) }

    @Suppress("unused")
    fun packBool(value: Boolean) { bout.putBit(if (value) 1 else 0) }
    fun packReal(value: Double) {
        bout.putBits(value.toRawBits().toULong(), 64)
    }

    fun packBoolean(value: Boolean) {
        bout.putBit(if (value) 1 else 0)
    }

}