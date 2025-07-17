package net.sergeych.lynon

import kotlinx.datetime.Instant
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*

open class LynonDecoder(val bin: BitInput, val settings: LynonSettings = LynonSettings.default) {

    val cache = mutableListOf<Obj>()

    inline fun decodeCached(f: LynonDecoder.() -> Obj): Obj {
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
            cache[id]
        }
    }

    fun decodeAny(scope: Scope): Obj = decodeCached {
        val type = LynonType.entries[bin.getBits(4).toInt()]
        return when (type) {
            LynonType.Null -> ObjNull
            LynonType.Int0 -> ObjInt.Zero
            LynonType.IntPositive -> ObjInt(bin.unpackUnsigned().toLong())
            LynonType.IntNegative -> ObjInt(-bin.unpackUnsigned().toLong())
            LynonType.Bool -> ObjBool(bin.getBit() == 1)
            LynonType.Real -> ObjReal(bin.unpackDouble())
            LynonType.Instant -> {
                val mode = LynonSettings.InstantTruncateMode.entries[bin.getBits(2).toInt()]
                when (mode) {
                    LynonSettings.InstantTruncateMode.Microsecond -> ObjInstant(
                        Instant.fromEpochSeconds(
                            bin.unpackSigned(), bin.unpackUnsigned().toInt() * 1000
                        )
                    )
                    LynonSettings.InstantTruncateMode.Millisecond -> ObjInstant(
                        Instant.fromEpochMilliseconds(
                            bin.unpackSigned()
                        )
                    )
                    LynonSettings.InstantTruncateMode.Second -> ObjInstant(
                        Instant.fromEpochSeconds(bin.unpackSigned())
                    )
                }
            }

            else -> {
                scope.raiseNotImplemented("lynon type $type")
            }
        }
    }

    fun unpackObject(scope: Scope, type: ObjClass): Obj {
        return decodeCached { type.deserialize(scope, this) }
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

}