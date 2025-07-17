package net.sergeych.lynon


class MemoryBitInput(val packedBits: UByteArray, val lastByteBits: Int) : BitInput {

    constructor(ba: BitArray) : this(ba.bytes, ba.lastByteBits) {}
    constructor(mba: MemoryBitOutput) : this(mba.toBitArray()) {}

    private var index = 0

    private var isEndOfStream: Boolean = packedBits.isEmpty() || (packedBits.size == 1 && lastByteBits == 0)
        private set

    /**
     * Return next byte, int in 0..255 range, or -1 if end of stream reached
     */
    private var accumulator = if( isEndOfStream ) 0 else packedBits[0].toInt()

    private var bitCounter = 0

    override fun getBitOrNull(): Int? {
        if (isEndOfStream) return null
        val result = accumulator and 1
        accumulator = accumulator shr 1
        bitCounter++
        // is end?
        if( index == packedBits.lastIndex && bitCounter == lastByteBits ) {
            isEndOfStream = true
        }
        else {
            if( bitCounter == 8 ) {
                bitCounter = 0
                accumulator = packedBits[++index].toInt()
            }
        }
        return result
    }


}