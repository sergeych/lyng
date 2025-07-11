package net.sergeych.lynon

/**
 * Hoq many tetrades needed to store the value. It is faster to use this function
 * than to use sizeInBits
 *
 * Size for 0 is 1
 */
fun sizeInTetrades(value: ULong): Int {
    if( value == 0UL ) return 1
    var size = 0
    var rest = value
    while( rest != 0UL ) {
        size++
        rest = rest shr 4
    }
    return size
}

/**
 * How many bits needed to store the value. Size for 0 is 1,
 */
@Suppress("unused")
fun sizeInBits(value: ULong): Int {
    if( value == 0UL ) return 1
    var size = 0
    var rest = value
    while( rest != 0UL ) {
        size++
        rest = rest shr 1
    }
    return size
}