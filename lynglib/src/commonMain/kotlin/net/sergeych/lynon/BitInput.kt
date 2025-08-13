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

interface BitInput {


    fun getBitOrNull(): Int?

    fun getBitsOrNull(count: Int): ULong? {
        var result = 0UL
        var resultMask = 1UL
        for( i in 0 ..< count) {
            when(getBitOrNull()) {
                null -> return null
                1 -> result = result or resultMask
                0 -> {}
            }
            resultMask = resultMask shl 1
        }
        return result
    }

    fun getBits(count: Int): ULong {
        return getBitsOrNull(count) ?: throw IllegalStateException("Unexpected end of stream")
    }

    fun getBit(): Int {
        return getBitOrNull() ?: throw IllegalStateException("Unexpected end of stream")
    }

    fun unpackUnsigned(): ULong =
        unpackUnsignedOrNull() ?: throw IllegalStateException("Unexpected end of stream")

    fun unpackUnsignedOrNull(): ULong? {
        val tetrades = getBitsOrNull(4)?.toInt() ?: return null
        var result = 0UL
        var shift = 0
        for (i in 0.. tetrades) {
            result = result or (getBits(4) shl shift)
            shift += 4
        }
        return result
    }

    fun unpackSigned(): Long {
        val isNegative = getBit()
        val value = unpackUnsigned().toLong()
        return if( isNegative == 1) -value else value
    }

    @Suppress("unused")
    fun getBool(): Boolean {
        return getBit() == 1
    }

    fun getBytes(count: Int): ByteArray? {
        val result = ByteArray(count)
        for (i in 0..<count) {
            val b = getBitsOrNull(8) ?: return null
            result[i] = b.toByte()
        }
        return result
    }


    fun decompress(): ByteArray = decompressOrNull() ?: throw DecompressionException("Unexpected end of stream")

    fun decompressOrNull(): ByteArray? {
        val originalSize = unpackUnsignedOrNull()?.toInt() ?: return null
        return if( getBit() == 1) {
            // data is compressed
//            val expectedCRC = getBits(32).toUInt()
            val method = getBits(2).toInt()
            if( method != 0) throw DecompressionException("Unknown compression method")
            LZW.decompress(this, originalSize).asByteArray()
        }
        else {
            getBytes(originalSize) ?: throw DecompressionException("Unexpected end of stream in uncompressed data")
        }
    }

    @Suppress("unused")
    fun decompressStringOrNull(): String? = decompressOrNull()?.decodeToString()

    fun decompressString(): String = decompress().decodeToString()

    @Suppress("unused")
    fun unpackDouble(): Double {
        val bits = getBits(64)
        return Double.fromBits(bits.toLong())
    }
}

