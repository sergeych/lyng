package net.sergeych.lynon

import net.sergeych.bintools.ByteChunk
import kotlin.math.roundToInt

/**
 * LZW lightweight pure kotlin compression.
 */
object LZW {

    val MAX_CODE_SIZE = 17
    val STOP_CODE = (1 shl MAX_CODE_SIZE) - 1
    val MAX_DICT_SIZE = (STOP_CODE * 0.92).roundToInt()


    fun compress(input: ByteArray, bitOutput: BitOutput)
        = compress(input.asUByteArray(), bitOutput)

    /**
     * Compresses the input string using LZW algorithm
     * @param input The string to compress
     * @return List of compressed codes
     */
    fun compress(input: UByteArray, bitOutput: BitOutput) {
        // Initialize dictionary with all possible single characters
        val dictionary = mutableMapOf<ByteChunk, Int>()
        for (i in 0..255) {
            // 23
            dictionary[ByteChunk(ubyteArrayOf(i.toUByte()))] = i
        }

        var nextCode = 256
        var current = ByteChunk(ubyteArrayOf())
//            val result = mutableListOf<Int>()

        for (char in input) {
            val combined = current + char
            if (dictionary.containsKey(combined)) {
                current = combined
            } else {
                val size = sizeInBits(dictionary.size)
                bitOutput.putBits(dictionary[current]!!, size)
                if (dictionary.size >= MAX_DICT_SIZE) {
                    bitOutput.putBits(STOP_CODE, size)
                    dictionary.clear()
                    nextCode = 256
                    for (i in 0..255) {
                        dictionary[ByteChunk(ubyteArrayOf(i.toUByte()))] = i
                    }
                } else
                    dictionary[combined] = nextCode++
                current = ByteChunk(ubyteArrayOf(char))
            }
        }

        if (current.size > 0) {
            val size = sizeInBits(dictionary.size)
            bitOutput.putBits(dictionary[current]!!, size)
        }
    }

    fun compress(input: UByteArray): BitArray {
        return MemoryBitOutput().apply {
            compress(input, this)
        }.toBitArray()
    }

    /**
     * Decompresses a list of LZW codes back to the original string. Note that usage of apriori existing
     * size is crucial: it let repeal explosion style attacks.
     *
     * @param compressed The list of compressed codes
     * @param resultSize The expected size of the decompressed string
     *
     * @throws DecompressionException if something goes wrong
     * @return The decompressed string
     */
    fun decompress(compressed: BitInput, resultSize: Int): UByteArray {
        // Initialize dictionary with all possible single characters
        val dictionary = mutableMapOf<Int, UByteArray>()
        for (i in 0..255) {
            dictionary[i] = ubyteArrayOf(i.toUByte())
        }

        var nextCode = 256
        val firstCode = compressed.getBits(9).toInt()
        var previous = dictionary[firstCode]
            ?: throw DecompressionException("Invalid first compressed code: $firstCode")
        val result = mutableListOf<UByte>()
        result += previous

        while (result.size < resultSize) {
            val codeSize = sizeInBits(nextCode + 1)
            val code = compressed.getBitsOrNull(codeSize)?.toInt() ?: break

            if (code == STOP_CODE) {
                nextCode = 256
                dictionary.clear()
                for (i in 0..255)
                    dictionary[i] = ubyteArrayOf(i.toUByte())
                previous = dictionary[compressed.getBits(9).toInt()]!!
            } else {

                val current = if (code in dictionary) {
                    dictionary[code]!!
                } else if (code == nextCode) {
                    // Special case for pattern like cScSc
                    previous + previous[0]
                } else {
                    throw DecompressionException("Invalid compressed code: $code")
                }

                result += current
                dictionary[nextCode++] = previous + current[0]
                previous = current
            }
        }

        if (result.size != resultSize)
            throw DecompressionException("Decompressed size is not equal to expected: real/expected = ${result.size}/$resultSize")
        return result.toTypedArray().toUByteArray()
    }
}


private operator fun ByteChunk.plus(byte: UByte): ByteChunk {
    return ByteChunk(data + byte)
}
