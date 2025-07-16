import junit.framework.TestCase.*
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
class LynonTests {

    @Test
    fun testSizeInTetrades() {
        assertEquals(1, sizeInTetrades(0u))
        assertEquals(1, sizeInTetrades(1u))
        assertEquals(1, sizeInTetrades(15u))
        assertEquals(2, sizeInTetrades(16u))
        assertEquals(2, sizeInTetrades(254u))
        assertEquals(2, sizeInTetrades(255u))
        assertEquals(3, sizeInTetrades(256u))
        assertEquals(3, sizeInTetrades(257u))
    }

    @Test
    fun testSizeInBits() {
        assertEquals(1, sizeInBits(0u))
        assertEquals(1, sizeInBits(1u))
        assertEquals(2, sizeInBits(2u))
        assertEquals(2, sizeInBits(3u))
        assertEquals(4, sizeInBits(15u))
    }

    @Test
    fun testBitStreams() {

        val bout = MemoryBitOutput()
        bout.putBits(2, 3)
        bout.putBits(1, 7)
        bout.putBits(197, 8)
        bout.putBits(3, 4)
        bout.close()

        val bin = MemoryBitInput(bout)
        assertEquals(2UL, bin.getBits(3))
        assertEquals(1UL, bin.getBits(7))
        assertEquals(197UL, bin.getBits(8))
        assertEquals(3UL, bin.getBits(4))
    }

    @Test
    fun testUnsignedPackInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(1471792UL)
        bout.close()
        val bin = MemoryBitInput(bout)
        assertEquals(1471792UL, bin.unpackUnsigned())
    }

    @Test
    fun testUnsignedPackLongInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(ULong.MAX_VALUE)
        bout.close()
        val bin = MemoryBitInput(bout)
        assertEquals(ULong.MAX_VALUE, bin.unpackUnsigned())
    }

    @Test
    fun testUnsignedPackLongSmallInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(7UL)
        bout.close()
        val bin = MemoryBitInput(bout)
        assertEquals(7UL, bin.unpackUnsigned())
    }

    @Test
    fun testSignedPackInteger() {
        val bout = MemoryBitOutput()
        bout.packSigned(-1471792L)
        bout.packSigned(1471792L)
//        bout.packSigned(147179L)
        bout.close()
        val bin = MemoryBitInput(bout)
        assertEquals(-1471792L, bin.unpackSigned())
        assertEquals(1471792L, bin.unpackSigned())
    }

    @Test
    fun testCache1() = runTest {
        val bout = MemoryBitOutput()
        val encoder = LynonEncoder(bout)
        val s = "Hello, World!".toObj()
        val scope = Scope()
        encoder.encodeObj(scope, s) // 1
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s)
        encoder.encodeObj(scope, s) // 8

        val decoder = LynonDecoder(MemoryBitInput(bout))
        val s1 = decoder.unpackObject(scope, ObjString.type) // 1
        assertEquals(s, s1)
        assertNotSame(s, s1)
        val s2 = decoder.unpackObject(scope, ObjString.type)
        assertEquals(s, s2)
        assertSame(s1, s2)
        assertSame(s1, decoder.unpackObject(scope, ObjString.type))
        assertSame(s1, decoder.unpackObject(scope, ObjString.type))
        assertSame(s1, decoder.unpackObject(scope, ObjString.type))
        assertSame(s1, decoder.unpackObject(scope, ObjString.type))
        assertSame(s1, decoder.unpackObject(scope, ObjString.type))
        assertSame(s1, decoder.unpackObject(scope, ObjString.type)) // 8
    }

    @Test
    fun testCache2() = runTest {
        val variants = (100..500).map { "Sample $it".toObj() }.shuffled()
        var source = variants.shuffled()
        for (i in 0..300) source += variants.shuffled()
        val encoder = LynonPacker()
        val scope = Scope()
        for (s in source) {
            encoder.encodeObj(scope, s)
        }
        val decoder = LynonUnpacker(encoder)
        val restored = mutableListOf<Obj>()
        for (i in source.indices) {
            restored.add(decoder.unpackObject(scope, ObjString.type))
        }
        assertEquals(restored, source)
    }

    @Test
    fun testUnpackBoolean() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObj(scope, ObjBool(true))
            encodeObj(scope, ObjBool(false))
            encodeObj(scope, ObjBool(true))
            encodeObj(scope, ObjBool(true))
        })
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjFalse, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
    }

    @Test
    fun testUnpackReal() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObj(scope, ObjReal(-Math.PI))
            encodeObj(scope, ObjReal(Math.PI))
            encodeObj(scope, ObjReal(-Math.PI))
            encodeObj(scope, ObjReal(Math.PI))
            encodeObj(scope, ObjReal(Double.NaN))
            encodeObj(scope, ObjReal(Double.NEGATIVE_INFINITY))
            encodeObj(scope, ObjReal(Double.POSITIVE_INFINITY))
            encodeObj(scope, ObjReal(Double.MIN_VALUE))
            encodeObj(scope, ObjReal(Double.MAX_VALUE))
        })
        assertEquals(ObjReal(-Math.PI), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(Math.PI), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(-Math.PI), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(Math.PI), decoder.unpackObject(scope, ObjReal.type))
        assert((decoder.unpackObject(scope, ObjReal.type)).toDouble().isNaN())
        assertEquals(ObjReal(Double.NEGATIVE_INFINITY), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.POSITIVE_INFINITY), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.MIN_VALUE), decoder.unpackObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.MAX_VALUE), decoder.unpackObject(scope, ObjReal.type))
    }
    @Test
    fun testUnpackInt() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObj(scope, ObjInt(0))
            encodeObj(scope, ObjInt(-1))
            encodeObj(scope, ObjInt(23))
            encodeObj(scope, ObjInt(Long.MIN_VALUE))
            encodeObj(scope, ObjInt(Long.MAX_VALUE))
            encodeObj(scope, ObjInt(Long.MAX_VALUE))
        })
        assertEquals(ObjInt(0), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(-1), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(23), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MIN_VALUE), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.unpackObject(scope, ObjInt.type))
    }

    @Test
    fun testLastvalue() {
        var bin = MemoryBitInput(MemoryBitOutput().apply {
            putBits(5, 3)
        })
        assertEquals(5UL, bin.getBits(3))
        assertEquals(null, bin.getBitsOrNull(3))
        bin = MemoryBitInput(MemoryBitOutput().apply {
            putBits(5, 3)
            putBits(1024, 11)
            putBits(2, 2)
        })
        assertEquals(5UL, bin.getBits(3))
        assertEquals(1024UL, bin.getBits(11))
        assertEquals(2UL, bin.getBits(2))
        assertEquals(null, bin.getBitsOrNull(3))
    }


    val original = Files.readString(Path.of("../sample_texts/dikkens_hard_times.txt"))

    @Test
    fun testLzw() {
        // Example usage
//        val original = "TOBEORNOTTOBEORTOBEORNOT"
//        println("Original: $original")
        println("Length: ${original.length}")

        // Compress
        val out = MemoryBitOutput()
        LZW.compress(original.encodeToByteArray().toUByteArray(), out)
//        println("\nCompressed codes: ${out.toUByteArray().toDump()}")
        println("Number of codes: ${out.toBitArray().bytesSize}")
        println("Copression rate: ${out.toBitArray().bytesSize.toDouble() / original.length.toDouble()}")
//        // Decompress
        val decompressed = LZW.decompress(MemoryBitInput(out), original.length).toByteArray().decodeToString()
//        println("\nDecompressed: $decompressed")
        println("Length: ${decompressed.length}")

        // Verification
        println("\nOriginal and decompressed match: ${original == decompressed}")
        assertEquals(original, decompressed)
    }

    @Test
    fun testTinyBits() {
        var a0 = TinyBits()

        assertEquals(a0, a0)
        a0 = a0.insertBit(0)
        a0 = a0.insertBit(1)
        a0 = a0.insertBit(1)
        a0 = a0.insertBit(1)
        a0 = a0.insertBit(0)
        a0 = a0.insertBit(1)
//        println(a0)
        assertEquals("011101", a0.toString())
        val bin = MemoryBitInput(MemoryBitOutput().apply { putBits(a0) })
        var result = TinyBits()
        for( i in a0.indices)  result = result.insertBit(bin.getBit())
        assertEquals(a0, result)
    }

    @Test
    fun testHuffman() {
        val x = original.encodeToByteArray().toUByteArray()
//        val x ="hello, world!".toByteArray().asUByteArray()// original.encodeToByteArray().toUByteArray()
        println("Original : ${x.size}")
        val lzw = LZW.compress(x).bytes
        println("LZW      : ${lzw.size}")
        val ba = Huffman.compress(x)
        val huff = ba.bytes
        println("Huffman  : ${huff.size}")
        val lzwhuff = Huffman.compress(lzw).bytes
        println("LZW+HUFF : ${lzwhuff.size}")
        val compressed = Huffman.compress(x)
        val decompressed = Huffman.decompress(compressed.toBitInput())
        assertContentEquals(x, decompressed)
    }

    @Test
    fun testBitListSmall() {
        var t = TinyBits()
        for( i in listOf(1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 0, 1) )
            t = t.insertBit(i)
        assertEquals(1, t[0])
        assertEquals(1, t[1])
        assertEquals(0, t[2])
        assertEquals("1101000111101",t.toString())
        t[0] = 0
        t[1] = 0
        t[2] = 1
        assertEquals("0011000111101",t.toString())
        t[12] = 0
        t[11] = 1
        assertEquals("0011000111110",t.toString())
    }

    @Test
    fun testBitListSerialization() {
        // this also tests bitArray with first and last bytes
        val bout = MemoryBitOutput()
        assertEquals("1101", bitListOf(1, 1, 0, 1).toString())
        bout.putBits(bitListOf(1, 1, 0, 1))
        bout.putBits(bitListOf( 0, 0))
        bout.putBits(bitListOf( 0, 1, 1, 1, 1, 0, 1))
        val x = bout.toBitArray()
        assertEquals("1101000111101",x.toString())
    }


    @Test
    fun testCompressionWithOffsets() {
        val src = "to be or not to be or not to be or not to be or not to be"
        val bout = MemoryBitOutput()
        bout.packUnsigned(1571UL)
        LZW.compress(src.encodeToByteArray(), bout)
        bout.packUnsigned(157108UL)
        val bin = bout.toBitInput()
        assertEquals(1571UL, bin.unpackUnsigned())
        assertEquals(src, LZW.decompress(bin, src.length).asByteArray().decodeToString())
        assertEquals(157108UL, bin.unpackUnsigned())
    }

    @Test
    fun testCompressionRecord() {
        val bout = MemoryBitOutput()
        val src = "to be or not to be or not to be or not to be or not to be"
        val src2 = "to be or not to be"
        val src3 = "ababababab"
        bout.compress(src)
        bout.compress(src2)
        bout.compress(src3)
        val bin = bout.toBitInput()
        assertEquals(src, bin.decompressString())
        assertEquals(src2, bin.decompressString())
        assertEquals(src3, bin.decompressString())
    }

}

