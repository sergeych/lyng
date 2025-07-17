package net.sergeych.lynon

import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

enum class LynonType {
    Null,
    Int0,
    IntNegative,
    IntPositive,
    String,
    Real,
    Bool,
    List,
    Map,
    Set,
    Buffer,
    Instant,
    Duration,
    Other;
}

open class LynonEncoder(val bout: BitOutput,val settings: LynonSettings = LynonSettings.default) {

    val cache = mutableMapOf<Any, Int>()

    private suspend fun encodeCached(item: Any, packer: suspend LynonEncoder.() -> Unit) {

        suspend fun serializeAndCache(key: Any=item) {
            bout.putBit(0)
            if( settings.shouldCache(item) )
                cache[key] = cache.size
            packer()
        }

        when(item) {
            is Obj -> cache[item]?.let { cacheId ->
                val size = sizeInBits(cache.size)
                bout.putBit(1)
                bout.putBits(cacheId.toULong(), size)
            } ?: serializeAndCache()

            is ByteArray, is UByteArray ->  serializeAndCache()
        }
    }

    /**
     * Encode any Lyng object [Obj], which can be serialized, using type record. This allow to
     * encode any object with the overhead of type record.
     *
     * Caching is used automatically.
     */
    suspend fun encodeAny(scope: Scope,value: Obj) {
        encodeCached(value) {
            when(value) {
                is ObjNull -> putType(LynonType.Null)
                is ObjInt -> {
                    when {
                        value.value == 0L -> putType(LynonType.Int0)
                        value.value < 0 -> {
                            putType(LynonType.IntNegative)
                            encodeUnsigned((-value.value).toULong())
                        }
                        else -> {
                            putType(LynonType.IntPositive)
                            encodeUnsigned(value.value.toULong())
                        }
                    }
                }
                is ObjBool -> {
                    putType(LynonType.Bool)
                    encodeBoolean(value.value)
                }
                is ObjReal -> {
                    putType(LynonType.Real)
                    encodeReal(value.value)
                }
                is ObjInstant -> {
                    putType(LynonType.Instant)
                    bout.putBits(value.truncateMode.ordinal, 2)
                    // todo: favor truncation mode from ObjInstant
                    when(value.truncateMode) {
                        LynonSettings.InstantTruncateMode.Millisecond ->
                            encodeSigned(value.instant.toEpochMilliseconds())
                        LynonSettings.InstantTruncateMode.Second ->
                            encodeSigned(value.instant.epochSeconds)
                        LynonSettings.InstantTruncateMode.Microsecond -> {
                            encodeSigned(value.instant.epochSeconds)
                            encodeUnsigned(value.instant.nanosecondsOfSecond.toULong() / 1000UL)
                        }
                    }
                }
                else -> {
                    TODO()
                }
            }
        }
    }

    private fun putType(type: LynonType) {
        bout.putBits(type.ordinal.toULong(), 4)
    }

    suspend fun encodeObj(scope: Scope, obj: Obj) {
        encodeCached(obj) {
            obj.serialize(scope, this)
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

}