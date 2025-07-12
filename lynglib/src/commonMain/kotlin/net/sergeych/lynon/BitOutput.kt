package net.sergeych.lynon

abstract class BitOutput {

    abstract fun outputByte(byte: UByte)

    private var accumulator = 0

    /**
     * Number of bits in accumulator. After output is closed by [close] this value is
     * not changed and represents the number of bits in the last byte; this should
     * be used to properly calculate end of the bit stream
     */
    private var accumulatorBits = 0
        private set

    /**
     * When [close] is called, represents the number of used bits in the last byte;
     * bits after this number are the garbage and should be ignored
     */
    val lastByteBits: Int get() {
        if( !isClosed ) throw IllegalStateException("BitOutput is not closed")
        return accumulatorBits
    }

    fun putBits(bits: ULong, count: Int) {
        require(count <= 64)
        var x = bits
        for (i in 0..<count) {
            putBit((x and 1u).toInt())
            x = x shr 1
        }
    }

    fun putBits(bits: Int, count: Int) {
        require(count <= 32)
        var x = bits
        for (i in 0..<count) {
            putBit((x and 1))
            x = x shr 1
        }
    }

    fun putBit(bit: Int) {
        accumulator = (accumulator shl 1) or bit
        if (++accumulatorBits >= 8) {
            outputByte(accumulator.toUByte())
            accumulator = accumulator shr 0
            accumulatorBits = 0
        }
    }

    fun packUnsigned(value: ULong) {
        val tetrades = sizeInTetrades(value)
        putBits(tetrades - 1, 4)
        var rest = value
        for (i in 0..<tetrades) {
            putBits(rest and 0xFu, 4)
            rest = rest shr 4
        }
    }

    @Suppress("unused")
    fun packSigned(value: Long) {
        if (value < 0) {
            putBit(1)
            packUnsigned((-value).toULong())
        } else {
            putBit(0)
            packUnsigned(value.toULong())
        }
    }

    var isClosed = false
        private set

    fun close(): BitOutput {
        if (!isClosed) {
            if (accumulatorBits > 0) {
                outputByte(accumulator.toUByte())
            } else accumulatorBits = 8
            isClosed = true
        }
        return this
    }

    fun putBytes(data: ByteArray) {
        for (b in data) {
            putBits(b.toULong(), 8)
        }
    }

}