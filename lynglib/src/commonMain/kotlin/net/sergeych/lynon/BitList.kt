package net.sergeych.lynon

@Suppress("unused")
interface BitList {
    operator fun get(bitIndex: Long): Int
    operator fun set(bitIndex: Long,value: Int)
    val size: Long
    val indices: LongRange

    fun toInput(): BitInput = object : BitInput {
        private var index = 0L

        override fun getBitOrNull(): Int? =
            if( index < size) this@BitList[index++]
            else null
    }
}

fun bitListOf(vararg bits: Int): BitList {
    return if( bits.size > 64) {
        BitArray.ofBits(*bits)
    }
    else
        TinyBits.of(*bits)
}

@Suppress("unused")
fun bitListOfSize(sizeInBits: Long): BitList {
    return if( sizeInBits > 64) {
        BitArray.withBitSize(sizeInBits)
    }
    else
        TinyBits()
}