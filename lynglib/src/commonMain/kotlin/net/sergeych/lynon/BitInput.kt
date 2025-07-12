package net.sergeych.lynon

abstract class BitInput {

    data class DataByte(val data: Int,val bits: Int)

    /**
     * Return next byte, int in 0..255 range, or -1 if end of stream reached
     */
    abstract fun getByte(): DataByte

    private var accumulator = 0

    var isEndOfStream: Boolean = false
        private set

    private var mask = 0

    fun getBitOrNull(): Int? {
        if (isEndOfStream) return null
        if (mask == 0) {
            val ab = getByte()
            accumulator = ab.data
            if (accumulator == -1) {
                isEndOfStream = true
                return null
            }
            mask = 1 shl (ab.bits - 1)
        }
        val result = if (0 == accumulator and mask) 0 else 1
        mask = mask shr 1
        return result
    }

    fun getBitsOrNull(count: Int): ULong? {
        var result = 0UL
        var resultMask = 1UL
        for( i in 0 ..< count) {
            when(getBitOrNull()) {
                null -> return null
                1 -> result = result or resultMask
                0 -> {}
            }
            resultMask = resultMask shl 1
        }
        return result
    }

    fun getBits(count: Int): ULong {
        return getBitsOrNull(count) ?: throw IllegalStateException("Unexpected end of stream")
    }

    fun getBit(): Int {
        return getBitOrNull() ?: throw IllegalStateException("Unexpected end of stream")
    }

    fun unpackUnsigned(): ULong {
        val tetrades = getBits(4).toInt()
        var result = 0UL
        var shift = 0
        for (i in 0.. tetrades) {
            result = result or (getBits(4) shl shift)
            shift += 4
        }
        return result
    }

    fun unpackSigned(): Long {
        val isNegative = getBit()
        val value = unpackUnsigned().toLong()
        return if( isNegative == 1) -value else value
    }

    @Suppress("unused")
    fun getBool(): Boolean {
        return getBit() == 1
    }

    fun getBytes(count: Int): ByteArray? {
        val result = ByteArray(count)
        for (i in 0..<count) {
            val b = getBitsOrNull(8) ?: return null
            result[i] = b.toByte()
        }
        return result
    }
}

