import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class OOTest {
    @Test
    fun testClassProps() = runTest {
        eval("""
            import lyng.time
            
            class Point(x,y) {
                static val origin = Point(0,0)
                static var center = origin
            }
            assertEquals(Point(0,0), Point.origin)
            assertEquals(Point(0,0), Point.center)
            Point.center = Point(1,2)
            assertEquals(Point(0,0), Point.origin)
            assertEquals(Point(1,2), Point.center)
            
        """.trimIndent())
    }
    @Test
    fun testClassMethods() = runTest {
        eval("""
            import lyng.time
            
            class Point(x,y) {
                private static var data = null
                
                static fun getData() { data }
                static fun setData(value) { 
                    data = value 
                    callFrom()
                }
                static fun callFrom() {
                    data = data + "!"
                }
            }
            assertEquals(Point(0,0), Point(0,0) )
            assertEquals(null, Point.getData() )
            Point.setData("foo")
            assertEquals( "foo!", Point.getData() )
        """.trimIndent())
    }
}