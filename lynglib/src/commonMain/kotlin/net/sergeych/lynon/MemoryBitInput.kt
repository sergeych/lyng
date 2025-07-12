package net.sergeych.lynon

class MemoryBitInput(val packedBits: UByteArray,val lastByteBits: Int): BitInput() {

    constructor(bout: MemoryBitOutput): this(bout.toUByteArray(), bout.lastByteBits)

    private var index = 0

    override fun getByte(): DataByte {
        return if( index < packedBits.size ) {
            DataByte(
                packedBits[index++].toInt(),
                if( index == packedBits.size ) lastByteBits else 8
            )
        } else {
            DataByte(-1,0)
        }
    }

}