import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class TypesTest {

    @Test
    fun testTypeCollection1() = runTest {
        eval("""
           class Point(x: Real, y: Real)
           assert(Point(1,2).x == 1)
           assert(Point(1,2).y == 2)
           assert(Point(1,2) is Point)
        """.trimIndent())

    }
    @Test
    fun testTypeCollection2() = runTest {
        eval("""
           fun fn1(x: Real, y: Real): Real { x + y }
        """.trimIndent())

    }
    @Test
    fun testTypeCollection3() = runTest {
        eval("""
           class Test(a: Int) {
              fun fn1(x: Real, y: Real): Real { x + y }
           }
        """.trimIndent())
    }

    @Test
    fun testExternDeclarations() = runTest {
        eval("""
            extern fun foo1(a: String): Void
            assertThrows { foo1("1") }
           class Test(a: Int) {
              extern fun fn1(x: Real, y: Real): Real
//              extern val b: Int
           }
//           println("1")
           val t = Test(0)
//           println(t.b)
//           println("2")
           assertThrows {
            t.fn1(1,2)
           }
//           println("4")
           
        """.trimIndent())
    }
}