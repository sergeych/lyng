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


/**
 * Bit size-aware code, short [BitList] implementation, up to 64 bits (efficiency tradeoff).
 * E.g `Bits(0, 3) != Bits(0, 2). For longer, use [BitArray].
 *
 * Note that [bitListOf] creates [TinyBits] when possible.
 */
class TinyBits(initValue: ULong = 0U, override val size: Long = 0): BitList {

    private var bits: ULong = initValue

    constructor(value: ULong, size: Int): this(value, size.toLong()) {}

    override val indices: LongRange by lazy { 0..<size }

    override operator fun get(bitIndex: Long): Int {
        if( bitIndex !in indices) throw IndexOutOfBoundsException("index out of bounds: $bitIndex")
        val mask = 1UL shl (size - bitIndex - 1).toInt()
        return if (bits and mask != 0UL) 1 else 0
    }

    override fun set(bitIndex: Long, value: Int) {
        val mask = 1UL shl (size - bitIndex - 1).toInt()
        if( value == 1)
            bits = bits or mask
        else
            bits = bits and mask.inv()
    }

    override fun toString(): String {
        val result = StringBuilder()
        for (i in 0..<size) result.append(this[i])
        return result.toString()
    }

    val value by ::bits

    /**
     * Add bit shifting value to the left and return _new instance_
     */
    fun insertBit(bit: Int): TinyBits {
        return TinyBits((bits shl 1) or bit.toULong(), size + 1)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TinyBits

        if (size != other.size) return false
        if (bits != other.bits) return false

        return true
    }

    override fun hashCode(): Int {
        var result = size.hashCode()
        result = 31 * result + bits.hashCode()
        return result
    }


    companion object {
        fun of(vararg bits: Int): TinyBits {
            return TinyBits(0UL, bits.size).apply { bits.forEachIndexed { i, v -> this[i.toLong()] = v } }
        }
    }
}