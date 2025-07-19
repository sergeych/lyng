package net.sergeych.lynon

import net.sergeych.bintools.ByteChunk
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

enum class LynonType(val objClass: ObjClass) {
    Null(ObjNull.objClass),
    Int0(ObjInt.type),
    IntNegative(ObjInt.type),
    IntPositive(ObjInt.type),
    String(ObjString.type),
    Real(ObjReal.type),
    Bool(ObjBool.type),
    List(ObjList.type),
    Map(ObjMap.type),
    Set(ObjSet.type),
    Buffer(ObjBuffer.type),
    Instant(ObjInstant.type),
    Duration(ObjDuration.type),
    Other(Obj.rootObjectType);
}

open class LynonEncoder(val bout: BitOutput, val settings: LynonSettings = LynonSettings.default) {

    val cache = mutableMapOf<Any, Int>()

    suspend fun encodeCached(item: Any, packer: suspend LynonEncoder.() -> Unit) {

        suspend fun serializeAndCache(key: Any = item) {
            cache[key]?.let { cacheId ->
                val size = sizeInBits(cache.size)
                bout.putBit(1)
                bout.putBits(cacheId.toULong(), size)
            } ?: run {
                bout.putBit(0)
                if (settings.shouldCache(item))
                    cache[key] = cache.size
                packer()
            }
        }

        when (item) {
            is ByteArray -> serializeAndCache(ByteChunk(item.asUByteArray()))
            is UByteArray -> serializeAndCache(ByteChunk(item))
            else -> serializeAndCache(item)
        }
    }

    /**
     * Encode any Lyng object [Obj], which can be serialized, using type record. This allow to
     * encode any object with the overhead of type record.
     *
     * Caching is used automatically.
     */
    suspend fun encodeAny(scope: Scope, value: Obj) {
        encodeCached(value) {
            val type = value.lynonType()
            putType(type)
            value.serialize(scope, this, type)
        }
    }

    private fun putType(type: LynonType) {
        bout.putBits(type.ordinal.toULong(), 4)
    }

    suspend fun encodeObject(scope: Scope, obj: Obj) {
        encodeCached(obj) {
            obj.serialize(scope, this, null)
        }
    }

    fun encodeBinaryData(data: ByteArray) {
        bout.compress(data)
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

    fun putBits(value: Int, sizeInBits: Int) {
        bout.putBits(value.toULong(), sizeInBits)
    }

    fun putBit(bit: Int) {
        bout.putBit(bit)
    }

}