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

package net.sergeych.lyng.tools

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Source
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.stdlib_included.rootLyng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LyngLanguageToolsTest {

    @Test
    fun languageTools_dryRun_rootLyng_hasNoErrors() = runTest {
        val res = LyngLanguageTools.analyze(rootLyng, "root.lyng")
        assertNotNull(res.mini, "root.lyng should build Mini-AST")
        assertTrue(res.lexicalHighlights.isNotEmpty(), "root.lyng should produce lexical highlights")
    }

    @Test
    fun languageTools_tracks_inner_and_type_aliases() = runTest {
        val code = """
            /** Box docs */
            type Box<T> = List<T?>

            class Outer {
                type Alias = Box<Int>
                class Inner {
                    val value: Int = 1
                }
                enum Kind { A, B }
                object Obj { val flag = true }
            }
        """.trimIndent()
        val res = LyngLanguageTools.analyze(code, "inner.lyng")
        val mini = res.mini
        assertNotNull(mini, "Mini-AST must be built")

        val outer = mini.declarations.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "Outer" }
        assertNotNull(outer, "Outer class should be captured")
        val aliasMember = outer.members.filterIsInstance<MiniMemberTypeAliasDecl>().firstOrNull { it.name == "Alias" }
        assertNotNull(aliasMember, "Inner type alias should be captured as a class member")

        val sem = LyngLanguageTools.semanticHighlights(res)
        assertTrue(sem.any { it.kind == LyngSemanticKind.TypeAlias }, "Type aliases should be part of semantic highlights")
    }

    @Test
    fun languageTools_completion_and_docs_for_type_alias() = runTest {
        val code = """
            /** Box docs */
            type Box<T> = List<T>
            val x: Box<Int> = [1]
            <caret>
        """.trimIndent()
        val caret = code.indexOf("<caret>")
        val text = code.replace("<caret>", "")
        val res = LyngLanguageTools.analyze(text, "alias.lyng")
        val items = LyngLanguageTools.completions(text, caret, res)
        assertTrue(items.any { it.name == "Box" }, "Completion should include Box type alias")

        val aliasOffset = text.indexOf("Box<Int>")
        val doc = LyngLanguageTools.docAt(res, aliasOffset)
        assertNotNull(doc, "Docs should resolve for Box")
        assertEquals("Box", doc.target.name)
        assertEquals("Box docs", doc.doc?.summary)
    }

    @Test
    fun languageTools_completion_includes_local_types() = runTest {
        val code = """
            fun f() {
                val local: String = "x"
                <caret>
            }
        """.trimIndent()
        val caret = code.indexOf("<caret>")
        val text = code.replace("<caret>", "")
        val res = LyngLanguageTools.analyze(text, "locals.lyng")
        val items = LyngLanguageTools.completions(text, caret, res)
        val local = items.firstOrNull { it.name == "local" }
        assertNotNull(local, "Completion should include local")
        assertTrue(local.typeText?.contains("String") == true, "Expected type for local, got ${local.typeText}")
    }

    @Test
    fun languageTools_definition_and_usages() = runTest {
        val code = """
            val answer = 42
            println(answer)
            answer
        """.trimIndent()
        val res = LyngLanguageTools.analyze(code, "usage.lyng")
        val usageOffset = code.lastIndexOf("answer")
        val def = LyngLanguageTools.definitionAt(res, usageOffset)
        assertNotNull(def, "Definition should resolve")
        assertEquals("answer", def.name)
        val usages = LyngLanguageTools.usagesAt(res, usageOffset)
        assertTrue(usages.size >= 2, "Expected at least two usages, got ${usages.size}")
    }

    @Test
    fun languageTools_disassemble_symbol() = runTest {
        val code = """
            fun add(a: Int, b: Int): Int {
                a + b
            }
        """.trimIndent()
        val dis = LyngLanguageTools.disassembleSymbol(code, "add")
        assertTrue(!dis.contains("not a compiled body"), "Disassembly should be produced, got: $dis")
    }

    @Test
    fun languageTools_semanticHighlights_ignore_foreign_sources() {
        val localSource = Source("local.lyng", "val x = 1")
        val foreignSource = Source("defs.lyng.d", "val y = 2")
        val localStart = Pos(localSource, 0, 0)
        val foreignStart = Pos(foreignSource, 0, 0)

        val mini = MiniScript(
            range = MiniRange(localStart, localStart),
            declarations = mutableListOf(
                MiniValDecl(
                    range = MiniRange(foreignStart, foreignStart),
                    name = "y",
                    mutable = false,
                    type = null,
                    initRange = null,
                    doc = null,
                    nameStart = foreignStart
                )
            ),
            imports = mutableListOf(
                MiniImport(
                    range = MiniRange(foreignStart, foreignStart),
                    segments = listOf(
                        MiniImport.Segment("defs", MiniRange(foreignStart, foreignStart))
                    )
                )
            )
        )
        val analysis = LyngAnalysisResult(
            source = localSource,
            text = localSource.text,
            mini = mini,
            binding = null,
            resolution = null,
            importedModules = emptyList(),
            diagnostics = emptyList(),
            lexicalHighlights = emptyList()
        )

        val spans = LyngLanguageTools.semanticHighlights(analysis)
        assertTrue(spans.isEmpty(), "Semantic spans should ignore positions from foreign sources, got $spans")
    }
}
