package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.Obj

open class LynonEncoder(val bout: BitOutput,val settings: LynonSettings = LynonSettings.default) {

    val cache = mutableMapOf<Any, Int>()

    private inline fun encodeCached(item: Any, packer: LynonEncoder.() -> Unit) {
        if (item is Obj) {
            cache[item]?.let { cacheId ->
                val size = sizeInBits(cache.size)
                bout.putBit(1)
                bout.putBits(cacheId.toULong(), size)
            } ?: run {
                bout.putBit(0)
                if (settings.shouldCache(item))
                    cache[item] = cache.size
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