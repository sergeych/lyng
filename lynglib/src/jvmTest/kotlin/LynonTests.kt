import junit.framework.TestCase.assertEquals
import net.sergeych.bintools.toDump
import net.sergeych.lynon.MemoryBitInput
import net.sergeych.lynon.MemoryBitOutput
import net.sergeych.lynon.sizeInTetrades
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
    fun testBitStreams() {

        val bout = MemoryBitOutput()
        bout.putBits(2, 3)
        bout.putBits(1, 7)
        bout.putBits( 197, 8)
        bout.putBits( 3, 4)
        bout.close()

        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(2UL, bin.getBits(3))
        assertEquals(1UL, bin.getBits(7))
        assertEquals(197UL, bin.getBits(8))
        assertEquals(3UL, bin.getBits(4))
    }

    @Test
    fun testPackInteger() {
        val bout = MemoryBitOutput()
        bout.packUnsigned(147179UL)
        bout.close()
        println(bout.toUByteArray().toDump())
        val bin = MemoryBitInput(bout.toUByteArray())
        assertEquals(147179UL, bin.unpackUnsigned())
    }

}