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

import net.sergeych.collections.SortedList
import net.sergeych.lynon.Huffman.Alphabet


/**
 * Generic huffman encoding implementation using bits input/output and abstract [Alphabet].
 */
object Huffman {

    /**
     * Alphabet interface: source can be variable bit size codes, not just bytes,
     * so the Huffman encoding is not limited to bytes. It works with any alphabet
     * using its _ordinals_; encoding between source symbols and ordinals are
     * performed by the alphabet. See [byteAlphabet] for example.
     */
    interface Alphabet<T> {
        val maxOrdinal: Int

        /**
         * Write correct symbol for the [ordinal] to the [bout]. This is
         * the inverse of [ordinalOf] but as [T] could be variable bit size,
         * we provide output bit stream.
         */
        fun decodeOrdinalTo(bout: BitOutput, ordinal: Int)

        /**
         * Find the ordinal of the source symbol
         */
        fun ordinalOf(value: T): Int

        operator fun get(ordinal: Int): T
    }

    /**
     * Alphabet for unsigned bytes, allows to encode bytes easily
     */
    val byteAlphabet = object : Alphabet<UByte> {
        override val maxOrdinal: Int
            get() = 256

        override fun decodeOrdinalTo(bout: BitOutput, ordinal: Int) {
            bout.putBits(ordinal, 8)
        }

        override fun ordinalOf(value: UByte): Int = value.toInt()

        override operator fun get(ordinal: Int): UByte = ordinal.toUByte()
    }

    sealed class Node(val freq: Int) : Comparable<Node> {
        override fun compareTo(other: Node): Int {
            return freq.compareTo(other.freq)
        }

        abstract fun decodeOrdinal(bin: BitInput): Int?

        class Leaf(val ordinal: Int, freq: Int) : Node(freq) {
            override fun toString(): String {
                return "[$ordinal:$freq]"
            }

            override fun decodeOrdinal(bin: BitInput): Int {
                return ordinal//.also { println(": ${Char(value)}") }
            }
        }

        class Internal(val left: Node, val right: Node) : Node(left.freq + right.freq) {
            override fun toString(): String {
                return "[${left.freq}<- :<$freq>: ->${right.freq}]"
            }

            override fun decodeOrdinal(bin: BitInput): Int? {
                return when (bin.getBitOrNull().also { print("$it") }) {
                    1 -> left.decodeOrdinal(bin)
                    0 -> right.decodeOrdinal(bin)
                    else -> null
                }
            }
        }
    }

    data class Code(val ordinal: Int, val bits: TinyBits) {

        val size by bits::size

        override fun toString(): String {
            return "[$ordinal:$size:$bits]"
        }

    }

    private fun generateCanonicCodes(tree: Node, alphabet: Alphabet<*>): List<Code?> {
        val codes = MutableList<Code?>(alphabet.maxOrdinal) { null }

        fun traverse(node: Node, code: TinyBits) {
            when (node) {
                is Node.Leaf ->
                    codes[node.ordinal] = (Code(node.ordinal, code))

                is Node.Internal -> {
                    traverse(node.left, code.insertBit(1))
                    traverse(node.right, code.insertBit(0))
                }
            }
        }
        traverse(tree, TinyBits())

        return makeCanonical(codes, alphabet)
    }

    private fun makeCanonical(source: List<Code?>,alphabet: Alphabet<*>): List<Code?> {
        val sorted = source.filterNotNull().sortedWith(canonicComparator)

        val canonical = MutableList<Code?>(alphabet.maxOrdinal) { null }

        val first = sorted[0]
        val prevValue = first.copy(bits = TinyBits(0UL, first.bits.size))
        canonical[first.ordinal] = prevValue
        var prev = prevValue.bits

        for (i in 1..<sorted.size) {
            var bits = TinyBits(prev.value + 1U, prev.size)
            val code = sorted[i]
            while (code.bits.size > bits.size) {
                bits = bits.insertBit(0)
            }
            canonical[code.ordinal] = code.copy(bits = bits)//.also { println("$it") }
            prev = bits
        }
        return canonical
    }

    private val canonicComparator = { a: Code, b: Code ->
        if (a.bits.size == b.bits.size) {
            a.ordinal.compareTo(b.ordinal)
        } else {
            a.bits.size.compareTo(b.bits.size)
        }
    }

    private fun buildTree(data: Iterable<Int>,alphabet: Alphabet<*>): Node {
        val frequencies = buildFrequencies(alphabet, data)
        return buildTree(frequencies)
    }

    private fun buildTree(frequencies: Array<Int>): Node {
//        println(data.toDump())

        val list: SortedList<Node> = SortedList(*frequencies.mapIndexed { index, frequency -> Node.Leaf(index, frequency) }.filter { it.freq > 0 }
            .toTypedArray())

        // build the tree
        while (list.size > 1) {
            val left = list.removeAt(0)
            val right = list.removeAt(0)
            list.add(Node.Internal(left, right))
        }
        return list[0]
    }

    private fun buildFrequencies(
        alphabet: Alphabet<*>,
        data: Iterable<Int>
    ): Array<Int> {
        val maxOrdinal = alphabet.maxOrdinal
        val frequencies = Array(maxOrdinal) { 0 }
        data.forEach { frequencies[it]++ }
        return frequencies
    }

    fun decompressUsingCodes(bin: BitInput, codes: List<Code?>, alphabet: Alphabet<*>): BitArray {
        val result = MemoryBitOutput()
        val table = codes.filterNotNull().associateBy { it.bits }

        outer@ while (true) {
            var input = TinyBits()
            while (true) {
                bin.getBitOrNull()?.let { input = input.insertBit(it) }
                    ?: break@outer
                val data = table[input]
                if (data != null) {
//                    println("Code found: ${data.bits} -> [${data.symbol.toChar()}]")
                    alphabet.decodeOrdinalTo(result,data.ordinal)
                    break
                }
            }
        }
        return result.toBitArray()
    }

    private fun serializeCanonicCodes(bout: BitOutput, codes: List<Code?>) {
        var minSize: Int? = null
        var maxSize: Int? = null
        for (i in 1..<codes.size) {
            val s = codes[i]?.size?.toInt() ?: continue
            if (minSize == null || s < minSize) minSize = s
            if (maxSize == null || s > maxSize) maxSize = s
        }
        val size = maxSize!! - minSize!! + 1
        val sizeInBits = sizeInBits(size)
        bout.packUnsigned(minSize.toULong())
        bout.packUnsigned(sizeInBits.toULong())
        for (c in codes) {
            if (c != null)
                bout.putBits(c.bits.size.toInt() - minSize + 1, sizeInBits)
            else
                bout.putBits(0, sizeInBits)
        }
    }

    fun deserializeCanonicCodes(bin: BitInput, alphabet: Alphabet<*>): List<Code?> {
        val minSize = bin.unpackUnsigned().toInt()
        val sizeInBits = bin.unpackUnsigned().toInt()
        val sorted = mutableListOf<Code>().also { codes ->
            for (i in 0..<alphabet.maxOrdinal) {
                val s = bin.getBits(sizeInBits).toInt()
                if (s > 0) {
                    codes.add(Code(i, TinyBits(0U, s - 1 + minSize)))
                }
            }
        }.sortedWith(canonicComparator)

        val result = MutableList<Code?>(alphabet.maxOrdinal) { null }
        var prev = sorted[0].copy(bits = TinyBits(0U, sorted[0].bits.size))
        result[prev.ordinal] = prev

        for (i in 1..<sorted.size) {
            val code = sorted[i]
            var bits = TinyBits(prev.bits.value + 1u, prev.bits.size)
            while (bits.size < code.bits.size) bits = bits.insertBit(0)
            result[code.ordinal] = code.copy(bits = bits).also {
                prev = it
            }
        }
        return result
    }

//    fun generateCanonicalCodes(frequencies: Iterable<Int>): List<Code?> {
//
//    }

    fun generateCanonicalCodes(frequencies: Array<Int>,alphabet: Alphabet<*>): List<Code?> =
        generateCanonicCodes(buildTree(frequencies), alphabet)

    fun <T>compress(plain: Iterable<T>,alphabet: Alphabet<T>): BitArray {

        val source = plain.map { alphabet.ordinalOf(it) }
        val root = buildTree(source,alphabet)

        val codes = generateCanonicCodes(root, alphabet)

        // serializa table

        // test encode:
        val bout = MemoryBitOutput()
        serializeCanonicCodes(bout, codes)
        for (i in source) {
            val code = codes[i]!!
//            println(">> $code")
            bout.putBits(code.bits)
        }
//        println(bout.toBitArray().bytes.toDump())
        val compressed = bout.toBitArray()
        return compressed
    }

    fun <T>decompress(bin: BitInput,alphabet: Alphabet<T>): UByteArray {
        val codes = deserializeCanonicCodes(bin, alphabet)
        return decompressUsingCodes(bin, codes, alphabet).asUbyteArray()
    }

}