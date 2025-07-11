package net.sergeych.lynon

class MemoryBitInput(val packedBits: UByteArray): BitInput() {
    constructor(bout: MemoryBitOutput): this(bout.toUByteArray())

    private var index = 0

    override fun getByte(): Int {
        if( index < packedBits.size ) {
            return packedBits[index++].toInt()
        } else {
            return -1
        }
    }

}