package net.sergeych.lynon


class MemoryBitInput(val packedBits: UByteArray, val lastByteBits: Int) : BitInput {

    constructor(ba: BitArray) : this(ba.bytes, ba.lastByteBits) {}
    constructor(mba: MemoryBitOutput) : this(mba.toBitArray()) {}

    private var index = 0

    /**
     * Return next byte, int in 0..255 range, or -1 if end of stream reached
     */
    private var accumulator = 0

    private var isEndOfStream: Boolean = false
        private set

    private var mask = 0

    override fun getBitOrNull(): Int? {
        if (isEndOfStream) return null
        if (mask == 0) {
            if (index < packedBits.size) {
                accumulator = packedBits[index++].toInt()
                val n = if (index == packedBits.size) lastByteBits else 8
                mask = 1 shl (n - 1)
            } else {
                isEndOfStream = true
                return null
            }
        }
        val result = if (0 == accumulator and mask) 0 else 1
        mask = mask shr 1
        return result
    }


}