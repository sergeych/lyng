import junit.framework.TestCase.*
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Scope
import net.sergeych.lyng.obj.*
import net.sergeych.lynon.*
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

        val bin = MemoryBitInput(bout.toUByteArray())
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
        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(1471792UL, bin.unpackUnsigned())
    }

    @Test
    fun testUnsignedPackLongInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(ULong.MAX_VALUE)
        bout.close()
        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(ULong.MAX_VALUE, bin.unpackUnsigned())
    }

    @Test
    fun testUnsignedPackLongSmallInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(7UL)
        bout.close()
        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(7UL, bin.unpackUnsigned())
    }

    @Test
    fun testSignedPackInteger() {
        val bout = MemoryBitOutput()
        bout.packSigned(-1471792L)
        bout.packSigned(1471792L)
//        bout.packSigned(147179L)
        bout.close()
        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(-1471792L, bin.unpackSigned())
        assertEquals(1471792L, bin.unpackSigned())
    }

    @Test
    fun testCache1() = runTest {
        val bout = MemoryBitOutput()
        val encoder = LynonEncoder(bout)
        val s = "Hello, World!".toObj()
        val scope = Scope()
        encoder.packObject(scope, s) // 1
        encoder.packObject(scope, s)
        encoder.packObject(scope, s)
        encoder.packObject(scope, s)
        encoder.packObject(scope, s)
        encoder.packObject(scope, s)
        encoder.packObject(scope, s)
        encoder.packObject(scope, s) // 8

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
            encoder.packObject(scope, s)
        }
        val decoder = LynonUnpacker(encoder.toUByteArray())
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
            packObject(scope, ObjBool(true))
            packObject(scope, ObjBool(false))
            packObject(scope, ObjBool(true))
            packObject(scope, ObjBool(true))
        }.toUByteArray())
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjFalse, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
        assertEquals(ObjTrue, decoder.unpackObject(scope, ObjBool.type))
    }

    @Test
    fun testUnpackReal() = runTest {
        val scope = Scope()
        val decoder = LynonUnpacker(LynonPacker().apply {
            packObject(scope, ObjReal(-Math.PI))
            packObject(scope, ObjReal(Math.PI))
            packObject(scope, ObjReal(-Math.PI))
            packObject(scope, ObjReal(Math.PI))
            packObject(scope, ObjReal(Double.NaN))
            packObject(scope, ObjReal(Double.NEGATIVE_INFINITY))
            packObject(scope, ObjReal(Double.POSITIVE_INFINITY))
            packObject(scope, ObjReal(Double.MIN_VALUE))
            packObject(scope, ObjReal(Double.MAX_VALUE))
        }.toUByteArray())
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
            packObject(scope, ObjInt(0))
            packObject(scope, ObjInt(-1))
            packObject(scope, ObjInt(23))
            packObject(scope, ObjInt(Long.MIN_VALUE))
            packObject(scope, ObjInt(Long.MAX_VALUE))
            packObject(scope, ObjInt(Long.MAX_VALUE))
        }.toUByteArray())
        assertEquals(ObjInt(0), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(-1), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(23), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MIN_VALUE), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.unpackObject(scope, ObjInt.type))
        assertEquals(ObjInt(Long.MAX_VALUE), decoder.unpackObject(scope, ObjInt.type))
    }
}