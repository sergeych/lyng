package net.sergeych.lynon

class MemoryBitOutput: BitOutput() {
    private val buffer = mutableListOf<UByte>()

    fun toUByteArray(): UByteArray {
        close()
        return buffer.toTypedArray().toUByteArray()
    }

    override fun outputByte(byte: UByte) {
        buffer.add(byte)
    }
}