import junit.framework.TestCase.assertNotSame
import junit.framework.TestCase.assertSame
import kotlinx.coroutines.test.runTest
import net.sergeych.bintools.encodeToHex
import net.sergeych.lyng.Scope
import net.sergeych.lyng.eval
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun testBitOutputSmall() {
        val bout = MemoryBitOutput()
        bout.putBit(1)
        bout.putBit(1)
        bout.putBit(0)
        bout.putBit(1)
        val x = bout.toBitArray()
        assertEquals(1, x[0])
        assertEquals(1, x[1])
        assertEquals(0, x[2])
        assertEquals(1, x[3])
        assertEquals(4, x.size)
        assertEquals("1101", x.toString())
        val bin = MemoryBitInput(x)
        assertEquals(1, bin.getBit())
        assertEquals(1, bin.getBit())
        assertEquals(0, bin.getBit())
        assertEquals(1, bin.getBit())
        assertEquals(null, bin.getBitOrNull())
    }

    @Test
    fun testBitOutputMedium() {
        val bout = MemoryBitOutput()
        bout.putBit(1)
        bout.putBit(1)
        bout.putBit(0)
        bout.putBit(1)
        bout.putBits(0, 7)
        bout.putBits(3, 2)
        val x = bout.toBitArray()
        assertEquals(1, x[0])
        assertEquals(1, x[1])
        assertEquals(0, x[2])
        assertEquals(1, x[3])
        assertEquals(13, x.size)
        assertEquals("1101000000011", x.toString())
        println(x.bytes.encodeToHex())
        val bin = MemoryBitInput(x)
        assertEquals(1, bin.getBit())
        assertEquals(1, bin.getBit())
        assertEquals(0, bin.getBit())
        assertEquals(1, bin.getBit())

//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
//        assertEquals(0, bin.getBit())
        assertEquals(0UL, bin.getBits(7))
        assertEquals(3UL, bin.getBits(2))
        assertEquals(null, bin.getBitOrNull())
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
    fun testObjStringAndStringKeys() = runTest {
        val s = "foo"
        val sobj = ObjString("foo")
        val map = mutableMapOf(s to 1, sobj to 2)
        assertEquals(1, map[s])
        assertEquals(2, map[sobj])
    }

    @Test
    fun testCache1() = runTest {
        val bout = MemoryBitOutput()
        val encoder = LynonEncoder(bout)
        val s = "Hello, World!".toObj()
        val scope = Scope()
        encoder.encodeObject(scope, s) // 1
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s)
        encoder.encodeObject(scope, s) // 8

        val decoder = LynonDecoder(MemoryBitInput(bout))
        val s1 = decoder.decodeObject(scope, ObjString.type) // 1
        assertEquals(s, s1)
        assertNotSame(s, s1)
        val s2 = decoder.decodeObject(scope, ObjString.type)
        assertEquals(s, s2)
        assertSame(s1, s2)
        assertSame(s1, decoder.decodeObject(scope, ObjString.type))
        assertSame(s1, decoder.decodeObject(scope, ObjString.type))
        assertSame(s1, decoder.decodeObject(scope, ObjString.type))
        assertSame(s1, decoder.decodeObject(scope, ObjString.type))
        assertSame(s1, decoder.decodeObject(scope, ObjString.type))
        assertSame(s1, decoder.decodeObject(scope, ObjString.type)) // 8
    }

    @Test
    fun testCache2() = runTest {
        val variants = (100..500).map { "Sample $it".toObj() }.shuffled()
        var source = variants.shuffled()
        for (i in 0..300) source += variants.shuffled()
        val encoder = LynonPacker()
        val scope = Scope()
        for (s in source) {
            encoder.encodeObject(scope, s)
        }
        val decoder = LynonUnpacker(encoder)
        val restored = mutableListOf<Obj>()
        for (i in source.indices) {
            restored.add(decoder.decodeObject(scope, ObjString.type))
        }
        assertEquals(restored, source)
    }

    @Test
    fun testUnpackBoolean() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObject(scope, ObjBool(true))
            encodeObject(scope, ObjBool(false))
            encodeObject(scope, ObjBool(true))
            encodeObject(scope, ObjBool(true))
        })
        assertEquals(ObjTrue, decoder.decodeObject(scope, ObjBool.type))
        assertEquals(ObjFalse, decoder.decodeObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.decodeObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.decodeObject(scope, ObjBool.type))
    }

    @Test
    fun testUnpackReal() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObject(scope, ObjReal(-Math.PI))
            encodeObject(scope, ObjReal(Math.PI))
            encodeObject(scope, ObjReal(-Math.PI))
            encodeObject(scope, ObjReal(Math.PI))
            encodeObject(scope, ObjReal(Double.NaN))
            encodeObject(scope, ObjReal(Double.NEGATIVE_INFINITY))
            encodeObject(scope, ObjReal(Double.POSITIVE_INFINITY))
            encodeObject(scope, ObjReal(Double.MIN_VALUE))
            encodeObject(scope, ObjReal(Double.MAX_VALUE))
        })
        assertEquals(ObjReal(-Math.PI), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(Math.PI), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(-Math.PI), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(Math.PI), decoder.decodeObject(scope, ObjReal.type))
        assert((decoder.decodeObject(scope, ObjReal.type)).toDouble().isNaN())
        assertEquals(ObjReal(Double.NEGATIVE_INFINITY), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.POSITIVE_INFINITY), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.MIN_VALUE), decoder.decodeObject(scope, ObjReal.type))
        assertEquals(ObjReal(Double.MAX_VALUE), decoder.decodeObject(scope, ObjReal.type))
    }

    @Test
    fun testUnpackInt() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            encodeObject(scope, ObjInt(0))
            encodeObject(scope, ObjInt(-1))
            encodeObject(scope, ObjInt(23))
            encodeObject(scope, ObjInt(Long.MIN_VALUE))
            encodeObject(scope, ObjInt(Long.MAX_VALUE))
            encodeObject(scope, ObjInt(Long.MAX_VALUE))
        })
        assertEquals(ObjInt(0), decoder.decodeObject(scope, ObjInt.type))
        assertEquals(ObjInt(-1), decoder.decodeObject(scope, ObjInt.type))
        assertEquals(ObjInt(23), decoder.decodeObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MIN_VALUE), decoder.decodeObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.decodeObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.decodeObject(scope, ObjInt.type))
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
    fun testEncodeNullsAndInts() = runTest {
        testScope().eval(
            """
            testEncode(null)
            testEncode(0)
        """.trimIndent()
        )
    }

    @Test
    fun testBufferEncoderInterop() = runTest {
        val bout = MemoryBitOutput()
        bout.putBits(0, 1)
        bout.putBits(1, 4)
        val bin = MemoryBitInput(bout.toBitArray().bytes, 8)
        assertEquals(0UL, bin.getBits(1))
        assertEquals(1UL, bin.getBits(4))
    }

    suspend fun testScope() =
        Scope().apply {
            eval(
                """
            import lyng.serialization
            fun testEncode(value) {
                val encoded = Lynon.encode(value)
                println(encoded.toDump())
                println("Encoded size %d: %s"(encoded.size, value))
                assertEquals( value, Lynon.decode(encoded) )
            }
            """.trimIndent()
            )
        }


    @Test
    fun testUnaryMinus() = runTest {
        eval(
            """
            assertEquals( -1 * π, 0 - π )
            assertEquals( -1 * π, -π )
            """.trimIndent()
        )
    }

    @Test
    fun testSimpleTypes() = runTest {
        testScope().eval(
            """
            testEncode(null)
            testEncode(0)
            testEncode(47)
            testEncode(-21)
            testEncode(true)
            testEncode(false)
            testEncode(1.22345)
            testEncode(-π)

            import lyng.time
            testEncode(Instant.now().truncateToSecond())
            testEncode(Instant.now().truncateToMillisecond())
            testEncode(Instant.now().truncateToMicrosecond())

            testEncode("Hello, world".encodeUtf8())
            testEncode("Hello, world")
            
        """.trimIndent()
        )
    }

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
        for (i in a0.indices) result = result.insertBit(bin.getBit())
        assertEquals(a0, result)
    }

    @Test
    fun testHuffman() {
        val x = original.encodeToByteArray().toUByteArray()
//        val x ="hello, world!".toByteArray().asUByteArray()// original.encodeToByteArray().toUByteArray()
        println("Original : ${x.size}")
        val lzw = LZW.compress(x).bytes
        println("LZW      : ${lzw.size}")
        val ba = Huffman.compress(x, Huffman.byteAlphabet)
        val huff = ba.bytes
        println("Huffman  : ${huff.size}")
        val lzwhuff = Huffman.compress(lzw, Huffman.byteAlphabet).bytes
        println("LZW+HUFF : ${lzwhuff.size}")
        val compressed = Huffman.compress(x, Huffman.byteAlphabet)
        val decompressed = Huffman.decompress(compressed.toBitInput(), Huffman.byteAlphabet)
        assertContentEquals(x, decompressed)
    }

    @Test
    fun testGenerateCanonicalHuffmanCodes() {
        val frequencies = LynonType.entries.map { it.defaultFrequency }.toTypedArray()
        val alphabet = object : Huffman.Alphabet<LynonType> {
            override val maxOrdinal = LynonType.entries.size

            override fun decodeOrdinalTo(bout: BitOutput, ordinal: Int) {
                throw NotImplementedError()
            }

            override fun get(ordinal: Int): LynonType {
                return LynonType.entries[ordinal]
            }

            override fun ordinalOf(value: LynonType): Int = value.ordinal
        }
        for (code in Huffman.generateCanonicalCodes(frequencies, alphabet)) {
            println("${code?.bits}: ${code?.ordinal?.let { LynonType.entries[it] }}")
        }
    }

    @Test
    fun testBitListSmall() {
        var t = TinyBits()
        for (i in listOf(1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 0, 1))
            t = t.insertBit(i)
        assertEquals(1, t[0])
        assertEquals(1, t[1])
        assertEquals(0, t[2])
        assertEquals("1101000111101", t.toString())
        t[0] = 0
        t[1] = 0
        t[2] = 1
        assertEquals("0011000111101", t.toString())
        t[12] = 0
        t[11] = 1
        assertEquals("0011000111110", t.toString())
    }

    @Test
    fun testBitListSerialization() {
        // this also tests bitArray with first and last bytes
        val bout = MemoryBitOutput()
        assertEquals("1101", bitListOf(1, 1, 0, 1).toString())
        bout.putBits(bitListOf(1, 1, 0, 1))
        bout.putBits(bitListOf(0, 0))
        bout.putBits(bitListOf(0, 1, 1, 1, 1, 0, 1))
        val x = bout.toBitArray()
        assertEquals("1101000111101", x.toString())
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

    @Test
    fun testIntList() = runTest {
        testScope().eval(
            """
            testEncode([1,2,3])
            testEncode([-1,-2,-3])
            testEncode([1,-2,-3])
            testEncode([0,1])
            testEncode([0,0,0])
            testEncode(["the", "the", "wall", "the", "wall", "wall"])
            testEncode([1,2,3, "the", "wall", "wall"])
            testEncode([false, false, false, true,true])
            testEncode([])
        """.trimIndent()
        )
    }

    @Test
    fun testNamedObject() = runTest {
        val s = testScope()
        val x = s.eval("""5 => 6""")
        assert(x is ObjMapEntry)
        val bout = MemoryBitOutput()
        val e = LynonEncoder(bout)
        e.encodeAny(s, x)
        val bin = bout.toBitInput()
        val d = LynonDecoder(bin)
        val x2 = d.decodeAny(s)
        println(x)
        println(x2)
        assertTrue { x.compareTo(s, x2) == 0}
        assertEquals(x, x2)

        s.eval("""
            testEncode( 1 => "one" )
            testEncode( [1 => "one", 1 => "one"] )
            testEncode( [1 => "one", 1 => "one", "foo" => "MapEntry"] )
        """.trimIndent())
    }

    @Test
    fun testHomogenousMap() = runTest {
        val s = testScope()
        s.eval("""
            testEncode( Map("one" => 1, "two" => 2) )
            testEncode( Map() )
        """.trimIndent())
    }

    @Test
    fun testHeterogeneousMap() = runTest {
        val s = testScope()
        s.eval("""
            testEncode(["one", 2])
            testEncode([1, "2"])
            testEncode( Map("one" => 1, 2 => 2) )
            testEncode( Map("one" => 1, 2 => "2") )
        """.trimIndent())
    }

    @Test
    fun testSetSerialization() = runTest {
        testScope().eval("""
            testEncode( Set("one", "two") )
            testEncode( Set() )
            testEncode( Set(1, "one", false) )
            testEncode( Set(true, true, false) )
        """.trimIndent())
    }

    @Test
    fun testClassSerializationNoInstanceVars() = runTest {
        testScope().eval("""
            import lyng.serialization
            
            class Point(x,y)
            
//            println( Lynon.encode(Point(0,0)).toDump() )
            testEncode(Point(0,0))
            testEncode(Point(10,11))
            testEncode(Point(-1,2))
            testEncode(Point(-1,-2))
            testEncode(Point("point!",-2))
            
        """.trimIndent())
    }

    @Test
    fun testClassSerializationWithInstanceVars() = runTest {
        testScope().eval("""
            import lyng.serialization
            
            class Point(x=0) {
                var y = 0
            }
            
            testEncode(Point())
            testEncode(Point(1))
            testEncode(Point(1).apply { y = 2 })
            testEncode(Point(10).also { it.y = 11 })
            
        """.trimIndent())
    }

    @Test
    fun testClassSerializationWithInstanceVars2() = runTest {
        testScope().eval("""
            import lyng.serialization
            
            var onInitComment = null
            
            class Point(x=0) {
                var y = 0
                var comment = null

                fun onDeserialized() {
                    onInitComment = comment
                }
            }
            
            testEncode(Point())
            testEncode(Point(1))
            testEncode(Point(1).apply { y = 2 })
            testEncode(Point(10).also { it.y = 11 })

            // important: class init is called before setting non-constructor fields
            // this is decessary, so deserialized fields are only available
            // after onDeserialized() call (if exists):
            // deserialized:
            testEncode(Point(10).also { it.y = 11; it.comment = "comment" })
            println("-- on init comment "+onInitComment)
            assertEquals("comment", onInitComment)
            
        """.trimIndent())
    }

}



