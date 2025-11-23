/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
 * Mini-AST capture tests
 */
package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.miniast.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiniAstTest {

    private suspend fun compileWithMini(code: String): Pair<Script, net.sergeych.lyng.miniast.MiniAstBuilder> {
        val sink = MiniAstBuilder()
        val script = Compiler.compileWithMini(code.trimIndent(), sink)
        return script to sink
    }

    @Test
    fun miniAst_captures_import_segments() = runTest {
        val code = """
            import lyng.stdlib
            val x = 1
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val imps = mini!!.imports
        assertTrue(imps.isNotEmpty(), "imports should be captured")
        val first = imps.first()
        val segNames = first.segments.map { it.name }
        assertEquals(listOf("lyng", "stdlib"), segNames)
        // Ensure ranges are valid and ordered
        for (seg in first.segments) {
            assertTrue(seg.range.start.line == first.range.start.line)
            assertTrue(seg.range.start.column <= seg.range.end.column)
        }
    }

    @Test
    fun miniAst_captures_fun_docs_and_types() = runTest {
        val code = """
            // Summary: does foo
            // details can be here
            fun foo(a: Int, b: pkg.String?): Boolean {
                true
            }
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val fn = mini!!.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "foo" }
        assertNotNull(fn, "function decl should be captured")
        // Doc
        assertNotNull(fn.doc)
        assertEquals("Summary: does foo", fn.doc!!.summary)
        assertTrue(fn.doc!!.raw.contains("details"))
        // Params
        assertEquals(2, fn.params.size)
        val p1 = fn.params[0]
        val p2 = fn.params[1]
        val t1 = p1.type as MiniTypeName
        assertEquals(listOf("Int"), t1.segments.map { it.name })
        assertEquals(false, t1.nullable)
        val t2 = p2.type as MiniTypeName
        assertEquals(listOf("pkg", "String"), t2.segments.map { it.name })
        assertEquals(true, t2.nullable)
        // Return type
        val rt = fn.returnType as MiniTypeName
        assertEquals(listOf("Boolean"), rt.segments.map { it.name })
        assertEquals(false, rt.nullable)
    }

    @Test
    fun miniAst_captures_val_type_and_doc() = runTest {
        val code = """
            // docs for x
            val x: List<String> = ["a", "b"]
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val vd = mini!!.declarations.filterIsInstance<net.sergeych.lyng.miniast.MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)
        assertNotNull(vd.doc)
        assertEquals("docs for x", vd.doc!!.summary)
        val ty = vd.type
        assertNotNull(ty)
        val gen = ty as MiniGenericType
        val base = gen.base as MiniTypeName
        assertEquals(listOf("List"), base.segments.map { it.name })
        assertEquals(1, gen.args.size)
        val arg0 = gen.args[0] as MiniTypeName
        assertEquals(listOf("String"), arg0.segments.map { it.name })
        assertEquals(false, gen.nullable)
        assertNotNull(vd.initRange)
    }

    @Test
    fun miniAst_captures_class_bases_and_doc() = runTest {
        val code = """
            /** Class C docs */
            class C: Base1, Base2 {}
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val cd = mini!!.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "C" }
        assertNotNull(cd)
        assertNotNull(cd.doc)
        assertTrue(cd.doc!!.raw.contains("Class C docs"))
        // Bases captured as plain names for now
        assertEquals(listOf("Base1", "Base2"), cd.bases)
    }
}
