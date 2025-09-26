/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lynon

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * BitList implementation as fixed suze array of bits; indexing works exactly same as if
 * [MemoryBitInput] is used with [MemoryBitInput.getBit]. See [MemoryBitOutput] for
 * bits order and more information.
 *
 * It is [BitList] - comparable, and provides valid [hashCode] and [equals], so it could
 * also be used as a key in maps.
 */
@Serializable
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
        if (byteIndex == bytes.lastIndex && i >= lastByteBits)
                throw IndexOutOfBoundsException("$bitIndex is out of bounds (last)")
        return byteIndex to (1 shl i)
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

    @Suppress("unused")
    fun asByteArray(): ByteArray = bytes.asByteArray()

    @Suppress("unused")
    fun asUByteArray(): UByteArray = bytes

    override fun equals(other: Any?): Boolean {
        return when(other) {
            is BitArray ->
                // important: size must match as trailing zero bits will generate false eq otherwise:
                size == other.size && bytes contentEquals other.bytes
            is BitList -> compareTo(other) == 0
            else -> false
        }
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
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

/**
 * [BitOutput] implementation that writes to a memory buffer, LSB first.
 *
 * Bits are stored in the least significant bits of the bytes. E.g. the first bit
 * added by [putBit] will be stored in the bit 0x01 of the first byte, the second bit
 * in the bit 0x02 of the first byte, etc.
 *
 * This allows automatic fill of the last byte with zeros. This is important when
 * using bytes stored from [asByteArray] or `asUbyteArray`. When converting to
 * bytes, automatic padding to byte size is applied. With such bit order, constructing
 * [BitInput] to read from [ByteArray.toUByteArray] result only provides 0 to 7 extra zeroes bits
 * at teh end which is often acceptable. To avoid this, use [toBitArray]; the [BitArray]
 * stores exact number of bits and [BitArray.toBitInput] provides [BitInput] that
 * decodes exactly same bits.
 *
 */
class MemoryBitOutput : BitOutput {
    private val buffer = mutableListOf<UByte>()

    private var accumulator = 0

    private var mask = 1

    override fun putBit(bit: Int) {
        when (bit) {
            0 -> {}
            1 -> accumulator = accumulator or mask
            else -> throw IllegalArgumentException("Bit must be 0 or 1")
        }
        mask = mask shl 1
        if(mask == 0x100) {
            mask = 1
            outputByte(accumulator.toUByte())
            accumulator = accumulator shr 8
        }
    }

    var isClosed = false
        private set

    fun close(): BitArray {
        if (!isClosed) {
            if (mask != 0x01) {
                outputByte(accumulator.toUByte())
            }
            isClosed = true
        }
        return toBitArray()
    }

    fun lastBits(): Int {
        check(isClosed)
        return when(mask) {
            0x01 -> 8   // means that all bits of the last byte are in use
            0x02 -> 1
            0x04 -> 2
            0x08 -> 3
            0x10 -> 4
            0x20 -> 5
            0x40 -> 6
            0x80 -> 7
            else -> throw IllegalStateException("Invalid state, mask=${mask.toString(16)}")
        }
    }

    fun toBitArray(): BitArray {
        if (!isClosed) {
            close()
        }
        return BitArray(buffer.toTypedArray().toUByteArray(), lastBits())
    }

    fun toBitInput(): BitInput = toBitArray().toBitInput()

    private fun outputByte(byte: UByte) {
        buffer.add(byte)
    }
}