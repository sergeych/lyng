/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
 * Mini binding tests for highlighting/editor features
 */
package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.miniast.MiniAstBuilder
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BindingTest {

    private suspend fun bind(code: String): net.sergeych.lyng.binding.BindingSnapshot {
        val src = code.trimIndent()
        val sink = MiniAstBuilder()
        Compiler.compileWithMini(src, sink)
        val mini = sink.build()
        assertNotNull(mini, "MiniScript should be built")
        return Binder.bind(src, mini)
    }

    @Test
    fun binds_params_and_locals() = runTest {
        val snap = bind(
            """
            fun f(a:Int){
                val x = 1
                a + x
            }
            """
        )
        // Expect at least one Parameter symbol "a" and one Value symbol "x"
        val aIds = snap.symbols.filter { it.name == "a" }.map { it.id }
        val xIds = snap.symbols.filter { it.name == "x" }.map { it.id }
        assertTrue(aIds.isNotEmpty())
        assertTrue(xIds.isNotEmpty())
        // Both should have at least one reference across any symbol with that name
        val aRefs = snap.references.count { it.symbolId in aIds }
        val xRefs = snap.references.count { it.symbolId in xIds }
        assertEquals(1, aRefs)
        assertEquals(1, xRefs)
    }

    @Test
    fun binds_top_level_val_usage() = runTest {
        val snap = bind(
            """
            val x = 1
            x + 1
            """
        )
        val xSym = snap.symbols.firstOrNull { it.name == "x" }
        assertNotNull(xSym)
        // One reference usage to top-level x
        val refs = snap.references.filter { it.symbolId == xSym.id }
        assertEquals(1, refs.size)
    }

    @Test
    fun shadowing_scopes() = runTest {
        val snap = bind(
            """
            val x = 1
            fun f(){
                val x = 2
                x
            }
            """
        )
        val allX = snap.symbols.filter { it.name == "x" }
        // Expect at least two x symbols (top-level and local)
        assertEquals(true, allX.size >= 2)
        // The single reference inside f body should bind to the inner x (containerId != null)
        val localXs = allX.filter { it.containerId != null }
        assertEquals(true, localXs.isNotEmpty())
        val localX = localXs.maxBy { it.declStart }
        val refsToLocal = snap.references.count { it.symbolId == localX.id }
        assertEquals(1, refsToLocal)
    }

    @Test
    fun class_fields_basic() = runTest {
        val snap = bind(
            """
            class C {
                val foo = 1
                fun bar(){ foo }
            }
            """
        )
        val fooField = snap.symbols.firstOrNull { it.name == "foo" }
        assertNotNull(fooField)
        // Should have at least one reference (usage in bar)
        val refs = snap.references.count { it.symbolId == fooField.id }
        assertEquals(1, refs)
    }

    @Test
    fun ctor_params_as_fields() = runTest {
        val snap = bind(
            """
            class C(val x:Int){
                fun f(){ x }
            }
            """
        )
        val xField = snap.symbols.firstOrNull { it.name == "x" }
        assertNotNull(xField)
        val refs = snap.references.count { it.symbolId == xField.id }
        assertEquals(1, refs)
    }

    class ObjA: Obj() {
        override val objClass = type

        companion object {
            val type = ObjClass("ObjA").apply {
                addFn("get1") {
                    ObjString("get1")
                }
            }
        }
    }

    @Test
    fun testShortFormMethod() = runTest {
        eval("""
            class A {
                fun get1() = "1"
                fun get2() = get1() + "-2"
                fun get3(): String = get2() + "-3"
                override fun toString() = "!"+get3()+"!"
            }
            assert(A().get3() == "1-2-3")
            assert(A().toString() == "!1-2-3!")
        """.trimIndent())
    }

    @Test
    fun testLateGlobalBinding() = runTest {
        val ms = Script.newScope()
        ms.eval("""
            extern class A {
                fun get1(): String
            }
            
            extern fun getA(): A
            
            fun getB(a: A) = a.get1() + "-2"
        """.trimIndent())

        ms.addFn("getA") {
            ObjA()
        }
        ms.addConst("A", ObjA.type)
        ms.eval("""
            assert(A() is A)
            assert(getA() is A)
            assertEquals(getB(getA()), "get1-2")
        """.trimIndent())
    }
}


