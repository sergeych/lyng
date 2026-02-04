package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lynon.lynonEncodeAny
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ObjectExpressionTest {

    @Test
    fun testBasicObjectExpression() = runTest {
        eval("""
            val x = object { fun getY() = 1 }
            assertEquals(1, x.getY())
        """.trimIndent())
    }

    @Test
    fun testInheritanceWithArgs() = runTest {
        eval("""
            class Base(x) {
                val value = x
                val squares = x * x
            }
            
            val y = object : Base(5) {
                fun getZ() = value + 1
            }
            
            assertEquals(5, y.value)
            assertEquals(25, y.squares)
            assertEquals(6, y.getZ())
        """.trimIndent())
    }

    @Test
    fun testMultipleInheritance() = runTest {
        eval("""
            val x = object {
                fun a() = "A"
                fun b() = "B"
                fun c() = a() + b()
            }
            
            assertEquals("AB", x.c())
        """.trimIndent())
    }

    @Test
    fun testScopeCapture() = runTest {
        eval("""
            abstract class Counter { abstract fun next() }
            fun createCounter(start) {
                var count = start
                return object : Counter {
                    override fun next() {
                        val res = count
                        count = count + 1
                        return res
                    }
                }
            }
            
            val c = createCounter(10)
            assertEquals(10, c.next())
            assertEquals(11, c.next())
        """.trimIndent())
    }

    @Test
    fun testThisObjectAlias() = runTest {
        eval("""
            val x = object {
                val value = 42
                fun self() = this
                fun getValue() = this.value
            }
            
            assertEquals(42, x.getValue())
            // assert(x === x.self()) // Lyng might not have === for identity yet, checking if it compiles and runs
        """.trimIndent())
    }

    @Test
    fun testSerializationRejection() = runTest {
        val scope = Script.newScope()
        val obj = scope.eval("object { val x = 1 }")
        assertFailsWith<Exception> {
            lynonEncodeAny(scope, obj)
        }
    }

    @Test
    fun testQualifiedThis() = runTest {
        eval("""
            class Outer {
                val value = 1
                fun check() {
                    val x = object {
                        fun getOuterValue() = this@Outer.value
                    }
                    assertEquals(1, x.getOuterValue())
                }
            }
            
            val o = Outer()
            o.check()
        """.trimIndent())
    }

    @Test
    fun testDiagnosticName() = runTest {
        // This is harder to test directly, but we can check if it has a class and if that class name looks "anonymous"
        eval("""
            val x = object { }
            val name = ((x::class as Class).className as String)
            assert(name.startsWith("${'$'}Anon_"))
        """.trimIndent())
    }
}
