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

interface BitOutput {

    fun putBits(bits: ULong, count: Int) {
        require(count <= 64)
        var x = bits
        for (i in 0..<count) {
            putBit((x and 1u).toInt())
            x = x shr 1
        }
    }

    fun putBits(bits: Int, count: Int) {
        require(count <= 32)
        var x = bits
        for (i in 0..<count) {
            putBit((x and 1))
            x = x shr 1
        }
    }

    fun putBit(bit: Int)

    fun putBits(bitList: BitList) {
        for (i in bitList.indices)
            putBit(bitList[i])
    }

    fun packUnsigned(value: ULong) {
        val tetrades = sizeInTetrades(value)
        putBits(tetrades - 1, 4)
        var rest = value
        for (i in 0..<tetrades) {
            putBits(rest and 0xFu, 4)
            rest = rest shr 4
        }
    }

    @Suppress("unused")
    fun packSigned(value: Long) {
        if (value < 0) {
            putBit(1)
            packUnsigned((-value).toULong())
        } else {
            putBit(0)
            packUnsigned(value.toULong())
        }
    }

    fun putBytes(data: ByteArray) {
        for (b in data) {
            putBits(b.toULong(), 8)
        }
    }


    /**
     * Create compressed record with content and size check. Compression works with _bytes_.
     *
     * Structure:
     *
     * | size | meaning                                          |
     * |------|--------------------------------------------------|
     * | packed unsigned | size of uncompressed content in bytes |
     * | 1               | 0 - not compressed, 1 - compressed |
     *
     * __If compressed__, then:
     *
     * | size | meaning                              |
     * |------|--------------------------------------|
     * |  2   | 00 - LZW, other combinations reserved|
     *
     * After this header compressed bits follow.
     *
     * __If not compressed,__ then source data follows as bit stream.
     *
     * Compressed block overhead is 3 bits, uncompressed 1.
     */
    fun compress(source: ByteArray) {
        // size
        packUnsigned(source.size.toULong())
        // check compression is effective?
        val compressed = LZW.compress(source.asUByteArray())
        // check that compression is effective including header bits size:
        if( compressed.size + 2 < source.size * 8L) {
            putBit(1)
            // LZW algorithm
            putBits(0, 2)
            // compressed data
            putBits(compressed)
        }
        else {
            putBit(0)
            putBytes(source)
        }
    }

    fun compress(source: String) {
        compress(source.encodeToByteArray())
    }

}