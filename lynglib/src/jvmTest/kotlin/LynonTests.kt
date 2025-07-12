import junit.framework.TestCase.*
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

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

    @Test
    fun testLzw() {
        // Example usage
//        val original = "TOBEORNOTTOBEORTOBEORNOT"
        val original = Files.readString(Path.of("../sample_texts/dikkens_hard_times.txt"))
//        println("Original: $original")
        println("Length: ${original.length}")

        // Compress
        val out = MemoryBitOutput()
        LZW.compress(original.encodeToByteArray().toUByteArray(), out)
//        println("\nCompressed codes: ${out.toUByteArray().toDump()}")
        println("Number of codes: ${out.toUByteArray().size}")

//        // Decompress
        val decompressed = LZW.decompress(MemoryBitInput(out)).toByteArray().decodeToString()
//        println("\nDecompressed: $decompressed")
        println("Length: ${decompressed.length}")

        // Verification
        println("\nOriginal and decompressed match: ${original == decompressed}")
    }
}