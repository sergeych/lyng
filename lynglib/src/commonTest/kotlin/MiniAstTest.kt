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
 * Mini-AST capture tests
 */
package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.highlight.offsetOf
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
        val imps = mini.imports
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
        val fn = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "foo" }
        assertNotNull(fn, "function decl should be captured")
        // Doc
        assertNotNull(fn.doc)
        assertEquals("Summary: does foo", fn.doc.summary)
        assertTrue(fn.doc.raw.contains("details"))
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
        val vd = mini.declarations.filterIsInstance<net.sergeych.lyng.miniast.MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)
        assertNotNull(vd.doc)
        assertEquals("docs for x", vd.doc.summary)
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
    fun miniAst_captures_class_doc_with_members() = runTest {
        val code = """
            /** Class C docs */
            class C {
                fun foo() {}
            }
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val cd = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "C" }
        assertNotNull(cd)
        assertNotNull(cd.doc, "Class doc should be preserved even with members")
        assertTrue(cd.doc.raw.contains("Class C docs"))
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
        val cd = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "C" }
        assertNotNull(cd)
        assertNotNull(cd.doc)
        assertTrue(cd.doc.raw.contains("Class C docs"))
        // Bases captured as plain names for now
        assertEquals(listOf("Base1", "Base2"), cd.bases)
    }

    @Test
    fun miniAst_captures_enum_entries_and_doc() = runTest {
        val code = """
            /** Enum E docs */
            enum E {
                A,
                B,
                C
            }
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val ed = mini.declarations.filterIsInstance<MiniEnumDecl>().firstOrNull { it.name == "E" }
        assertNotNull(ed)
        assertNotNull(ed.doc)
        assertTrue(ed.doc.raw.contains("Enum E docs"))
        assertEquals(listOf("A", "B", "C"), ed.entries)
        assertEquals(3, ed.entryPositions.size)
        assertEquals("E", ed.name)
    }

    @Test
    fun enum_to_synthetic_class_members() = runTest {
        val code = """
            enum MyEnum { V1, V2 }
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        
        // I'll check via aggregateClasses by mocking the registry or just checking it includes Enum base.
        val stdlib = BuiltinDocRegistry.docsForModule("lyng.stdlib")
        val enumBase = stdlib.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "Enum" }
        assertNotNull(enumBase, "Enum base class should be in stdlib")
        assertTrue(enumBase.members.any { it.name == "name" })
        assertTrue(enumBase.members.any { it.name == "ordinal" })

        // Check if aggregateClasses handles enums from local MiniScript
        val classes = DocLookupUtils.aggregateClasses(listOf("lyng.stdlib"), mini)
        val myEnum = classes["MyEnum"]
        assertNotNull(myEnum, "Local enum should be aggregated as a class")
        assertEquals(listOf("Enum"), myEnum.bases)
        assertTrue(myEnum.members.any { it.name == "entries" }, "Should have entries")
        assertTrue(myEnum.members.any { it.name == "valueOf" }, "Should have valueOf")
        assertTrue(myEnum.members.any { it.name == "V1" }, "Should have V1")
        assertTrue(myEnum.members.any { it.name == "V2" }, "Should have V2")
    }

    @Test
    fun complete_enum_members() = runTest {
        val code = """
            enum MyEnum { V1, V2 }
            val x = MyEnum.V1.<caret>
        """
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val names = items.map { it.name }.toSet()
        assertTrue(names.contains("name"), "Should contain name from Enum base")
        assertTrue(names.contains("ordinal"), "Should contain ordinal from Enum base")
    }

    @Test
    fun complete_enum_class_members() = runTest {
        val code = """
            enum MyEnum { V1, V2 }
            val x = MyEnum.<caret>
        """
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val names = items.map { it.name }.toSet()
        assertTrue(names.contains("entries"), "Should contain entries")
        assertTrue(names.contains("valueOf"), "Should contain valueOf")
        assertTrue(names.contains("V1"), "Should contain V1")
        assertTrue(names.contains("V2"), "Should contain V2")
    }

    @Test
    fun miniAst_captures_extern_docs() = runTest {
        val code = """
            // Doc1
            extern fun f1()
            
            // Doc2
            extern class C1 {
                // Doc3
                fun m1()
            }
            
            // Doc4
            extern object O1 {
                // Doc5
                val v1: String
            }
            
            // Doc6
            extern enum E1 {
                V1, V2
            }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        
        val f1 = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "f1" }
        assertNotNull(f1)
        assertEquals("Doc1", f1.doc?.summary)
        
        val c1 = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "C1" }
        assertNotNull(c1)
        assertEquals("Doc2", c1.doc?.summary)
        val m1 = c1.members.filterIsInstance<MiniMemberFunDecl>().firstOrNull { it.name == "m1" }
        assertNotNull(m1)
        assertEquals("Doc3", m1.doc?.summary)
        
        val o1 = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "O1" }
        assertNotNull(o1)
        assertTrue(o1.isObject)
        assertEquals("Doc4", o1.doc?.summary)
        val v1 = o1.members.filterIsInstance<MiniMemberValDecl>().firstOrNull { it.name == "v1" }
        assertNotNull(v1)
        assertEquals("Doc5", v1.doc?.summary)
        
        val e1 = mini.declarations.filterIsInstance<MiniEnumDecl>().firstOrNull { it.name == "E1" }
        assertNotNull(e1)
        assertEquals("Doc6", e1.doc?.summary)
    }

    @Test
    fun resolve_inferred_member_type() = runTest {
        val code = """
            object O3 {
                val name = "ozone"
            }
            val x = O3.name
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        val type = DocLookupUtils.findTypeByRange(mini, "x", code.indexOf("val x") + 4, code, emptyList())
        assertEquals("String", DocLookupUtils.simpleClassNameOf(type))
    }

    @Test
    fun resolve_inferred_val_type_from_extern_fun() = runTest {
        val code = """
            extern fun test(a: Int): List<Int>
            val x = test(1)
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val vd = mini.declarations.filterIsInstance<MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)

        val inferred = DocLookupUtils.inferTypeRefForVal(vd, code, emptyList(), mini)
        assertNotNull(inferred)
        assertTrue(inferred is MiniGenericType)
        assertEquals("List", (inferred.base as MiniTypeName).segments.last().name)

        val code2 = """
            extern fun test2(a: Int): String
            val y = test2(1)
        """.trimIndent()
        val (_, sink2) = compileWithMini(code2)
        val mini2 = sink2.build()
        val vd2 = mini2?.declarations?.filterIsInstance<MiniValDecl>()?.firstOrNull { it.name == "y" }
        assertNotNull(vd2)
        val inferred2 = DocLookupUtils.inferTypeRefForVal(vd2, code2, emptyList(), mini2)
        assertNotNull(inferred2)
        assertTrue(inferred2 is MiniTypeName)
        assertEquals("String", inferred2.segments.last().name)

        val code3 = """
            extern object API {
                fun getData(): List<String>
            }
            val x = API.getData()
        """.trimIndent()
        val (_, sink3) = compileWithMini(code3)
        val mini3 = sink3.build()
        val vd3 = mini3?.declarations?.filterIsInstance<MiniValDecl>()?.firstOrNull { it.name == "x" }
        assertNotNull(vd3)
        val inferred3 = DocLookupUtils.inferTypeRefForVal(vd3, code3, emptyList(), mini3)
        assertNotNull(inferred3)
        assertTrue(inferred3 is MiniGenericType)
        assertEquals("List", (inferred3.base as MiniTypeName).segments.last().name)
    }

    @Test
    fun resolve_inferred_val_type_cross_script() = runTest {
        val dCode = "extern fun test(a: Int): List<Int>"
        val mainCode = "val x = test(1)"

        val (_, dSink) = compileWithMini(dCode)
        val dMini = dSink.build()!!

        val (_, mainSink) = compileWithMini(mainCode)
        val mainMini = mainSink.build()!!

        // Merge manually
        val merged = mainMini.copy(declarations = (mainMini.declarations + dMini.declarations).toMutableList())

        val vd = merged.declarations.filterIsInstance<MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)

        val inferred = DocLookupUtils.inferTypeRefForVal(vd, mainCode, emptyList(), merged)
        assertNotNull(inferred)
        assertTrue(inferred is MiniGenericType)
        assertEquals("List", (inferred.base as MiniTypeName).segments.last().name)
    }

    @Test
    fun miniAst_captures_user_sample_extern_doc() = runTest {
        val code = """
            /*
              the plugin testing .d sample
            */
            extern fun test(value: Int): String
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val test = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "test" }
        assertNotNull(test, "function 'test' should be captured")
        assertNotNull(test.doc, "doc for 'test' should be captured")
        assertEquals("the plugin testing .d sample", test.doc.summary)
        assertTrue(test.isExtern, "function 'test' should be extern")
    }

    @Test
    fun resolve_object_member_doc() = runTest {
        val code = """
            object O3 {
                /* doc for name */
                fun name() = "ozone"
            }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)

        val imported = listOf("lyng.stdlib")
        // Simulate looking up O3.name
        val resolved = DocLookupUtils.resolveMemberWithInheritance(imported, "O3", "name", mini)
        assertNotNull(resolved)
        assertEquals("O3", resolved.first)
        assertEquals("doc for name", resolved.second.doc?.summary)
    }
    @Test
    fun miniAst_captures_nested_generics() = runTest {
        val code = """
            val x: Map<String, List<Int>> = {}
        """
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val vd = mini.declarations.filterIsInstance<MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)
        val ty = vd.type as MiniGenericType
        assertEquals("Map", (ty.base as MiniTypeName).segments.last().name)
        assertEquals(2, ty.args.size)
        
        val arg1 = ty.args[0] as MiniTypeName
        assertEquals("String", arg1.segments.last().name)
        
        val arg2 = ty.args[1] as MiniGenericType
        assertEquals("List", (arg2.base as MiniTypeName).segments.last().name)
        assertEquals(1, arg2.args.size)
        val innerArg = arg2.args[0] as MiniTypeName
        assertEquals("Int", innerArg.segments.last().name)
    }

    @Test
    fun verify_class_member_structure() = runTest {
        val code = """
            class A {
                fun member() {}
                val field = 1
            }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()!!
        println("[DEBUG_LOG] Mini declarations: ${mini.declarations.map { it.name }}")
        assertEquals(1, mini.declarations.size, "Should only have one top-level declaration (class A)")
        val cls = mini.declarations[0] as MiniClassDecl
        assertEquals("A", cls.name)
        assertEquals(2, cls.members.size, "Class A should have 2 members (member() and field)")
        assertTrue(cls.members.any { it.name == "member" && it is MiniMemberFunDecl })
        assertTrue(cls.members.any { it.name == "field" && it is MiniMemberValDecl })
    }

    @Test
    fun inferTypeForValWithInference() = runTest {
        val code = """
            extern fun test(): List<Int>
            val x = test()
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        
        val vd = mini.declarations.filterIsInstance<MiniValDecl>().firstOrNull { it.name == "x" }
        assertNotNull(vd)
        
        val imported = listOf("lyng.stdlib")
        val src = mini.range.start.source
        val type = DocLookupUtils.findTypeByRange(mini, "x", src.offsetOf(vd.nameStart), code, imported)
        assertNotNull(type)
        val className = DocLookupUtils.simpleClassNameOf(type)
        assertEquals("List", className)
    }

    @Test
    fun miniAst_captures_fun_with_type_and_default() = runTest {
        val code = """
            fun foo(a: Int, b: String = "ok"): Bool { true }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val fn = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "foo" }
        assertNotNull(fn)
        assertEquals(2, fn.params.size)
        assertEquals("a", fn.params[0].name)
        assertEquals("b", fn.params[1].name)
    }

    @Test
    fun miniAst_captures_dokka_tags() = runTest {
        val code = """
            /**
             * Testing tags.
             * @param x the x value
             * @param y the y value
             * @return some string
             * @throws Exception if failed
             */
            fun tagged(x: Int, y: Int): String { "" }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val fn = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "tagged" }
        assertNotNull(fn)
        val doc = fn.doc
        assertNotNull(doc)
        assertEquals("Testing tags.", doc.summary)
        
        val tags = doc.tags
        assertTrue(tags.containsKey("param"), "should have @param tags")
        assertEquals(listOf("x the x value", "y the y value"), tags["param"])
        assertEquals(listOf("some string"), tags["return"])
        assertEquals(listOf("Exception if failed"), tags["throws"])
    }

    @Test
    fun miniAst_captures_multiline_tags() = runTest {
        val code = """
            /**
             * Multi line tag.
             * @param x first line of x
             *          second line of x
             * @return return value
             */
            fun multiline(x: Int): Int { 0 }
        """.trimIndent()
        val (_, sink) = compileWithMini(code)
        val mini = sink.build()
        assertNotNull(mini)
        val fn = mini.declarations.filterIsInstance<MiniFunDecl>().firstOrNull { it.name == "multiline" }
        assertNotNull(fn)
        val doc = fn.doc
        assertNotNull(doc)
        
        val tags = doc.tags
        assertTrue(tags.containsKey("param"), "should have @param tags")
        val xParam = tags["param"]?.first() ?: ""
        assertTrue(xParam.contains("first line of x"), "should contain first line")
        assertTrue(xParam.contains("second line of x"), "should contain second line")
    }
}
