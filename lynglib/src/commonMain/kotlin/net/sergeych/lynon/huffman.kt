package net.sergeych.lynon

import net.sergeych.collections.SortedList

/**
 * Experimental, reference implementation of Huffman trees and encoding.
 *
 * This is a reference huffman encoding implementation not yet ready;
 * it was used to experiment with LZW, at the moment, LZW won the competition
 * for compressed module format for its speed and sufficiently small size/
 *
 * This is byte-based compressor which makes it not too interesting.
 *
 * TODO: convert to use various source dictionary
 *
 * reason: version thant compress bytes is not too interesting; particular alphabets
 * are often longer than byte bits and are often sparse, that requires another
 * codes serialization implementation
 */
object Huffman {

    sealed class Node(val freq: Int) : Comparable<Node> {
        override fun compareTo(other: Node): Int {
            return freq.compareTo(other.freq)
        }

        abstract fun decode(bin: BitInput): Int?

        class Leaf(val value: Int, freq: Int) : Node(freq) {
            override fun toString(): String {
                return "[$value:$freq]"
            }

            override fun decode(bin: BitInput): Int {
                return value//.also { println(": ${Char(value)}") }
            }
        }

        class Internal(val left: Node, val right: Node) : Node(left.freq + right.freq) {
            override fun toString(): String {
                return "[${left.freq}<- :<$freq>: ->${right.freq}]"
            }

            override fun decode(bin: BitInput): Int? {
                return when (bin.getBitOrNull().also { print("$it") }) {
                    1 -> left.decode(bin)
                    0 -> right.decode(bin)
                    else -> null
                }
            }
        }
    }

    data class Code(val symbol: Int, val bits: TinyBits) {

        val size by bits::size

        override fun toString(): String {
            return "[${Char(symbol)}:$size:$bits]"
        }

    }

    private fun generateCanonicCodes(tree: Node): List<Code?> {
        val codes = MutableList<Code?>(256) { null }

        fun traverse(node: Node, code: TinyBits) {
            when (node) {
                is Node.Leaf ->
                    codes[node.value] = (Code(node.value, code))

                is Node.Internal -> {
                    traverse(node.left, code.insertBit(1))
                    traverse(node.right, code.insertBit(0))
                }
            }
        }
        traverse(tree, TinyBits())

        return makeCanonical(codes)
    }

    private fun makeCanonical(source: List<Code?>): List<Code?> {
        val sorted = source.filterNotNull().sortedWith(canonicComparator)

        val canonical = MutableList<Code?>(256) { null }

        val first = sorted[0]
        val prevValue = first.copy(bits = TinyBits(0UL, first.bits.size))
        canonical[first.symbol] = prevValue
        var prev = prevValue.bits

        for (i in 1..<sorted.size) {
            var bits = TinyBits(prev.value + 1U, prev.size)
            val code = sorted[i]
            while (code.bits.size > bits.size) {
                bits = bits.insertBit(0)
            }
            canonical[code.symbol] = code.copy(bits = bits)//.also { println("$it") }
            prev = bits
        }
        return canonical
    }

    private val canonicComparator = { a: Code, b: Code ->
        if (a.bits.size == b.bits.size) {
            a.symbol.compareTo(b.symbol)
        } else {
            a.bits.size.compareTo(b.bits.size)
        }
    }

    private fun buildTree(data: UByteArray): Node {
//        println(data.toDump())
        val frequencies = Array(256) { 0 }
        data.forEach { frequencies[it.toInt()]++ }

        val list = SortedList<Node>(*frequencies.mapIndexed { index, i -> Node.Leaf(index, i) }.filter { it.freq > 0 }
            .toTypedArray())

        // build the tree
        while (list.size > 1) {
            val left = list.removeAt(0)
            val right = list.removeAt(0)
            list.add(Node.Internal(left, right))
        }
        return list[0]
    }

    fun decompressUsingCodes(bin: BitInput, codes: List<Code?>): UByteArray {
        val result = mutableListOf<UByte>()
        val table = codes.filterNotNull().associateBy { it.bits }

        outer@ while (true) {
            var input = TinyBits()
            while (true) {
                bin.getBitOrNull()?.let { input = input.insertBit(it) }
                    ?: break@outer
                val data = table[input]
                if (data != null) {
//                    println("Code found: ${data.bits} -> [${data.symbol.toChar()}]")
                    result.add(data.symbol.toUByte())
                    break
                }
            }
        }
        return result.toUByteArray()
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

    fun deserializeCanonicCodes(bin: BitInput): List<Code?> {
        val minSize = bin.unpackUnsigned().toInt()
        val sizeInBits = bin.unpackUnsigned().toInt()
        val sorted = mutableListOf<Code>().also { codes ->
            for (i in 0..<256) {
                val s = bin.getBits(sizeInBits).toInt()
                if (s > 0) {
                    codes.add(Code(i, TinyBits(0U, s - 1 + minSize)))
                }
            }
        }.sortedWith(canonicComparator)

        val result = MutableList<Code?>(256) { null }
        var prev = sorted[0].copy(bits = TinyBits(0U, sorted[0].bits.size))
        result[prev.symbol] = prev

        for (i in 1..<sorted.size) {
            val code = sorted[i]
            var bits = TinyBits(prev.bits.value + 1u, prev.bits.size)
            while (bits.size < code.bits.size) bits = bits.insertBit(0)
            result[code.symbol] = code.copy(bits = bits).also {
                prev = it
            }
        }
        return result
    }

    fun compress(data: UByteArray): BitArray {

        val root = buildTree(data)

        val codes = generateCanonicCodes(root)

        // serializa table

        // test encode:
        val bout = MemoryBitOutput()
        serializeCanonicCodes(bout, codes)
        for (i in data) {
            val code = codes[i.toInt()]!!
//            println(">> $code")
            bout.putBits(code.bits)
        }
//        println(bout.toBitArray().bytes.toDump())
        val compressed = bout.toBitArray()
//        println("Size: ${compressed.bytes.size / data.size.toDouble() }")
//        println("compression ratio: ${compressed.bytes.size / data.size.toDouble() }")

        // test decompress
//        val bin = MemoryBitInput(compressed)
//        val codes2 = deserializeCanonicCodes(bin)
//        for ((a, b) in codes.zip(codes2)) {
//            if (a != b) {
//                println("Codes mismatch: $a != $b")
//                break
//            }
//        }
//        require(codes == codes2)
//        val result = decompressUsingCodes(bin, codes2)
//
////        println(result.toUByteArray().toDump())
//        check(data contentEquals result.toUByteArray())
//        if( !(data contentEquals result.toUByteArray()) )
//            throw RuntimeException("Data mismatch")
//        println(data.toDump())
//
        return compressed
    }

    fun decompress(bin: BitInput): UByteArray {
        val codes = deserializeCanonicCodes(bin)
        return decompressUsingCodes(bin, codes)
    }

}