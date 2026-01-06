
import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.eval
import kotlin.test.Test

class IfNullAssignTest {

    @Test
    fun testBasicAssignment() = runTest {
        eval("""
            var x = null
            x ?= 42
            assertEquals(42, x)
            x ?= 100
            assertEquals(42, x)
        """.trimIndent())
    }

    @Test
    fun testPropertyAssignment() = runTest {
        eval("""
            class Box(var value)
            val b = Box(null)
            b.value ?= 10
            assertEquals(10, b.value)
            b.value ?= 20
            assertEquals(10, b.value)
        """.trimIndent())
    }

    @Test
    fun testIndexAssignment() = runTest {
        eval("""
            val a = [null, 1]
            a[0] ?= 10
            assertEquals(10, a[0])
            a[1] ?= 20
            assertEquals(1, a[1])
        """.trimIndent())
    }

    @Test
    fun testOptionalChaining() = runTest {
        eval("""
            class Inner(var value)
            class Outer(var inner)
            
            var o = null
            o?.inner?.value ?= 10 // should do nothing
            assertEquals(null, o)
            
            o = Outer(null)
            o?.inner?.value ?= 10 // should do nothing because inner is null
            assertEquals(null, o.inner)
            
            o.inner = Inner(null)
            o?.inner?.value ?= 42
            assertEquals(42, o.inner.value)
            o?.inner?.value ?= 100
            assertEquals(42, o.inner.value)
        """.trimIndent())
    }

    @Test
    fun testDoubleEvaluation() = runTest {
        eval("""
            var count = 0
            fun getIdx() {
                count = count + 1
                return 0
            }
            
            val a = [null]
            a[getIdx()] ?= 10
            
            // Current behavior: double evaluation happens in ObjRef for compound ops
            // getIdx() is called once for checking if null, and once for setting if it was null.
            assertEquals(10, a[0])
            assertEquals(2, count)
            
            a[getIdx()] ?= 20
            // If it's NOT null, it only evaluates once to check the value.
            assertEquals(10, a[0])
            assertEquals(3, count)
        """.trimIndent())
    }
    
    @Test
    fun testReturnValue() = runTest {
        eval("""
            var x = null
            val r1 = (x ?= 42)
            assertEquals(42, r1)
            assertEquals(42, x)
            
            val r2 = (x ?= 100)
            assertEquals(42, r2)
            assertEquals(42, x)
        """.trimIndent())
    }
}
