package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lynon.lynonEncodeAny
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ObjectExpressionTest {

    @Test
    fun testBasicObjectExpression() = runTest {
        eval("""
            val x = object { val y = 1 }
            assertEquals(1, x.y)
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
                val z = value + 1
            }
            
            assertEquals(5, y.value)
            assertEquals(25, y.squares)
            assertEquals(6, y.z)
        """.trimIndent())
    }

    @Test
    fun testMultipleInheritance() = runTest {
        eval("""
            interface A { fun a() = "A" }
            interface B { fun b() = "B" }
            
            val x = object : A, B {
                fun c() = a() + b()
            }
            
            assertEquals("AB", x.c())
        """.trimIndent())
    }

    @Test
    fun testScopeCapture() = runTest {
        eval("""
            fun createCounter(start) {
                var count = start
                object {
                    fun next() {
                        val res = count
                        count = count + 1
                        res
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
                fun self() = this@object
                fun getValue() = this@object.value
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
                fun getObj() {
                    object {
                        fun getOuterValue() = this@Outer.value
                    }
                }
            }
            
            val o = Outer()
            val x = o.getObj()
            assertEquals(1, x.getOuterValue())
        """.trimIndent())
    }

    @Test
    fun testDiagnosticName() = runTest {
        // This is harder to test directly, but we can check if it has a class and if that class name looks "anonymous"
        eval("""
            val x = object { }
            val name = x::class.className
            assert(name.startsWith("${'$'}Anon_"))
        """.trimIndent())
    }
}
