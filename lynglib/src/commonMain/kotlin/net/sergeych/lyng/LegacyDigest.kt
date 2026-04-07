/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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
 */

package net.sergeych.lyng

/**
 * Pure Kotlin/KMP implementation of legacy hash functions.
 *
 * SHA-1 is cryptographically broken and must not be used for security-sensitive
 * purposes (password hashing, digital signatures, etc.). It is retained here
 * solely for compatibility with legacy protocols and file formats that require it.
 */
internal object LegacyDigest {

    /**
     * Compute the SHA-1 digest of [input] and return it as a lowercase hex string.
     *
     * SHA-1 is **cryptographically insecure**. Use only for protocol compatibility.
     */
    fun sha1Hex(input: ByteArray): String {
        val digest = sha1(input)
        return buildString(40) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v < 16) append('0')
                append(v.toString(16))
            }
        }
    }

    private fun sha1(input: ByteArray): ByteArray {
        // Initial hash values
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        // Pre-processing: pad message to a multiple of 512 bits (64 bytes).
        // Append 0x80, then zeros, then the 64-bit big-endian bit-length.
        val msgLen = input.size
        val bitLen = msgLen.toLong() * 8L
        // Minimum padding: 1 byte (0x80) + 8 bytes (length) = 9 bytes.
        // Total length must be ≡ 0 (mod 64).
        val padded = run {
            val totalLen = ((msgLen + 1 + 8 + 63) / 64) * 64
            ByteArray(totalLen).also { buf ->
                input.copyInto(buf)
                buf[msgLen] = 0x80.toByte()
                // Big-endian 64-bit bit-length in the last 8 bytes
                for (i in 0..7) {
                    buf[totalLen - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xFF).toByte()
                }
            }
        }

        val w = IntArray(80)

        var blockStart = 0
        while (blockStart < padded.size) {
            // Build the 80-word message schedule
            for (i in 0..15) {
                val off = blockStart + i * 4
                w[i] = ((padded[off].toInt() and 0xFF) shl 24) or
                       ((padded[off + 1].toInt() and 0xFF) shl 16) or
                       ((padded[off + 2].toInt() and 0xFF) shl 8) or
                       (padded[off + 3].toInt() and 0xFF)
            }
            for (i in 16..79) {
                val x = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (x shl 1) or (x ushr 31)   // ROTL-1
            }

            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4

            for (t in 0..19) {
                val f = (b and c) or (b.inv() and d)
                val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x5A827999 + w[t]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }
            for (t in 20..39) {
                val f = b xor c xor d
                val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x6ED9EBA1 + w[t]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }
            for (t in 40..59) {
                val f = (b and c) or (b and d) or (c and d)
                val temp = ((a shl 5) or (a ushr 27)) + f + e + 0x8F1BBCDC.toInt() + w[t]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }
            for (t in 60..79) {
                val f = b xor c xor d
                val temp = ((a shl 5) or (a ushr 27)) + f + e + 0xCA62C1D6.toInt() + w[t]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }

            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            blockStart += 64
        }

        return ByteArray(20).also { out ->
            fun putInt(off: Int, v: Int) {
                out[off]     = (v ushr 24).toByte()
                out[off + 1] = (v ushr 16).toByte()
                out[off + 2] = (v ushr  8).toByte()
                out[off + 3] =  v.toByte()
            }
            putInt(0, h0); putInt(4, h1); putInt(8, h2); putInt(12, h3); putInt(16, h4)
        }
    }
}
