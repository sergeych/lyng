import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class StdlibTest {
    @Test
    fun testIterableFilter() = runTest {
        eval("""
            assertEquals([1,3,5,7], (1..8).filter{ it % 2 == 1 }.toList() )
            assertEquals([2,4,6,8], (1..8).filter{ it % 2 == 0 }.toList() )
        """.trimIndent())

    }

    @Test
    fun testFirstLast() = runTest {
        eval("""
            assertEquals(1, (1..8).first )
            assertEquals(8, (1..8).last )
        """.trimIndent())
    }

    @Test
    fun testTake() = runTest {
        eval("""
            assertEquals([1,2,3], (1..8).take(3).toList() )
            assertEquals([7,8], (1..8).takeLast(2).toList() )
        """.trimIndent())
    }

    @Test
    fun testRingBuffer() = runTest {
        eval("""
            val r = RingBuffer(3)
            assert( r is RingBuffer )
            assertEquals(0, r.size)
            assertEquals(3, r.capacity)
            
            r += 10
            assertEquals(1, r.size)
            assertEquals(10, r.first)
            
            r += 20
            assertEquals(2, r.size)
            assertEquals( [10, 20], r.toList() )
            
            r += 30
            assertEquals(3, r.size)
            assertEquals( [10, 20, 30], r.toList() )
            
            r += 40
            assertEquals(3, r.size)
            assertEquals( [20, 30, 40], r.toList() )
            
        """.trimIndent())
    }

    @Test
    fun testDrop() = runTest {
        eval("""
            assertEquals([7,8], (1..8).drop(6).toList() )
            assertEquals([1,2], (1..8).dropLast(6).toList() )
        """.trimIndent())
    }
}