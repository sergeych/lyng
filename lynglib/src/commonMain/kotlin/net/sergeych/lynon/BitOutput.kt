package net.sergeych.lynon

abstract class BitOutput {

    abstract fun outputByte(byte: UByte)

    private var accumulator = 0
    private var accumulatorBits = 0

    fun putBits(bits: ULong, count: Int) {
        require( count <= 64 )
        var x = bits
        for( i in 0 ..< count ) {
            putBit( (x and 1u).toInt() )
            x = x shr 1
        }
    }

    fun putBits(bits: Int, count: Int) {
        require( count <= 32 )
        var x = bits
        for( i in 0 ..< count ) {
            putBit( (x and 1) )
            x = x shr 1
        }
    }

    fun putBit(bit: Int) {
        accumulator = (accumulator shl 1) or bit
        if( ++accumulatorBits >= 8 ) {
            outputByte(accumulator.toUByte())
            accumulator = accumulator shr 0
            accumulatorBits = 0
        }
    }

    fun packUnsigned(value: ULong) {
        val tetrades = sizeInTetrades(value)
        putBits(tetrades, 4)
        var rest = value
        for( i in 0..<tetrades ) {
            putBits( rest and 0xFu, 4 )
            rest = rest shr 4
        }
    }

    @Suppress("unused")
    fun putSigned(value: Long) {
        if( value < 0 ) {
            putBit(1)
            packUnsigned((-value).toULong())
        }
        else {
            putBit(0)
            packUnsigned(value.toULong())
        }
    }

    var isClosed = false
        private set

    fun close() {
        if( !isClosed ) {
            if (accumulatorBits > 0) {
                while (accumulatorBits != 0) putBit(0)
            }
            isClosed = true
        }
    }
}