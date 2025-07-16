package net.sergeych.lynon

import kotlin.math.min

/**
 * BitList implementation as fixed suze array of bits; indexing works exactly same as if
 * [MemoryBitInput] is used with [MemoryBitInput.getBit].
 */
class BitArray(val bytes: UByteArray, val lastByteBits: Int) : BitList {

    val bytesSize: Int get() = bytes.size
    override val size by lazy { bytes.size * 8L - (8 - lastByteBits) }

    override val indices by lazy { 0..<size }

    /**
     * @return [BitInput] that can be used to read from this array
     */
    fun toBitInput(): BitInput = MemoryBitInput(bytes, lastByteBits)

    private fun getIndexAndMask(bitIndex: Long): Pair<Int, Int> {
        val byteIndex = (bitIndex / 8).toInt()
        if (byteIndex !in bytes.indices)
            throw IndexOutOfBoundsException("$bitIndex is out of bounds")
        val i = (bitIndex % 8).toInt()
        return byteIndex to (
                if (byteIndex == bytes.lastIndex) {
                    if (i >= lastByteBits)
                        throw IndexOutOfBoundsException("$bitIndex is out of bounds (last)")
                    1 shl (lastByteBits - i - 1)
                } else {
                    1 shl (7 - i)
                }
                )
    }

    override operator fun get(bitIndex: Long): Int =
        getIndexAndMask(bitIndex).let { (byteIndex, mask) ->
            if (bytes[byteIndex].toInt() and mask == 0) 0 else 1
        }

    override operator fun set(bitIndex: Long, value: Int) {
        require(value == 0 || value == 1)
        val (byteIndex, mask) = getIndexAndMask(bitIndex)
        if (value == 1)
            bytes[byteIndex] = bytes[byteIndex] or mask.toUByte()
        else
            bytes[byteIndex] = bytes[byteIndex] and mask.inv().toUByte()
    }

    override fun toString(): String {
        val result = StringBuilder()
        val s = min(size, 64)
        for (i in 0..<s) result.append(this[i])
        if (s < size) result.append("…")
        return result.toString()
    }

    companion object {

        fun withBitSize(size: Long): BitArray {
            val byteSize = ((size + 7) / 8).toInt()
            val lastByteBits = size % 8
            return BitArray(UByteArray(byteSize), lastByteBits.toInt())
        }

        fun ofBits(vararg bits: Int): BitArray {
            return withBitSize(bits.size.toLong()).apply {
                for (i in bits.indices) {
                    this[i.toLong()] = bits[i]
                }
            }
        }
    }

}

class MemoryBitOutput : BitOutput {
    private val buffer = mutableListOf<UByte>()

    private var accumulator = 0

    /**
     * Number of bits in accumulator. After output is closed by [close] this value is
     * not changed and represents the number of bits in the last byte; this should
     * be used to properly calculate end of the bit stream
     */
    private var accumulatorBits = 0
        private set

//    /**
//     * When [close] is called, represents the number of used bits in the last byte;
//     * bits after this number are the garbage and should be ignored
//     */
//    val lastByteBits: Int
//        get() {
//            if (!isClosed) throw IllegalStateException("BitOutput is not closed")
//            return accumulatorBits
//        }

    override fun putBit(bit: Int) {
        accumulator = (accumulator shl 1) or bit
        if (++accumulatorBits >= 8) {
            outputByte(accumulator.toUByte())
            accumulator = accumulator shr 8
            accumulatorBits = 0
        }
    }

    var isClosed = false
        private set

    fun close(): BitArray {
        if (!isClosed) {
            if (accumulatorBits > 0) {
                outputByte(accumulator.toUByte())
            } else accumulatorBits = 8
            isClosed = true
        }
        return toBitArray()
    }

    fun toBitArray(): BitArray {
        if (!isClosed) {
            close()
        }
        return BitArray(buffer.toTypedArray().toUByteArray(), accumulatorBits)
    }

    fun toBitInput(): BitInput = toBitArray().toBitInput()

    private fun outputByte(byte: UByte) {
        buffer.add(byte)
    }
}