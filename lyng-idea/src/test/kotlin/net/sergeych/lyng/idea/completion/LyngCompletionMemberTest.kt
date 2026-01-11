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

package net.sergeych.lyng.idea.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import net.sergeych.lyng.idea.util.DocsBootstrap
import net.sergeych.lyng.miniast.BuiltinDocRegistry
import net.sergeych.lyng.miniast.DocLookupUtils
import net.sergeych.lyng.miniast.MiniClassDecl

class LyngCompletionMemberTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = ""

    private fun complete(code: String): List<String> {
        myFixture.configureByText("test.lyng", code)
        val items = myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    private fun ensureDocs(imports: List<String>) {
        // Make sure external/bundled docs like lyng.io.fs are registered
        DocsBootstrap.ensure()
        // Touch modules to force stdlib lazy load and optional modules
        for (m in imports) BuiltinDocRegistry.docsForModule(m)
    }

    private fun aggregateMemberNames(className: String, imported: List<String>): Set<String> {
        val classes = DocLookupUtils.aggregateClasses(imported)
        val visited = mutableSetOf<String>()
        val result = linkedSetOf<String>()
        fun dfs(name: String) {
            val cls: MiniClassDecl = classes[name] ?: return
            for (m in cls.members) result.add(m.name)
            if (!visited.add(name)) return
            for (b in cls.bases) dfs(b)
        }
        dfs(className)
        // Conservative supplementation mirroring contributor behavior
        when (className) {
            "List" -> listOf("Collection", "Iterable").forEach { dfs(it) }
            "Array" -> listOf("Collection", "Iterable").forEach { dfs(it) }
        }
        return result
    }

    fun test_NoGlobalsAfterDot_IteratorFromLines() {
        val code = """
            import lyng.io.fs
            import lyng.stdlib

            val files = Path("../..").lines().<caret>
        """.trimIndent()
        val imported = listOf("lyng.io.fs", "lyng.stdlib")
        ensureDocs(imported)

        val items = complete(code)
        // Must not propose globals after dot
        assertFalse(items.contains("Path"))
        assertFalse(items.contains("Array"))
        assertFalse(items.contains("String"))

        // Should contain a reasonable subset of Iterator members
        val expected = aggregateMemberNames("Iterator", imported)
        // At least one expected member must appear
        val intersection = expected.intersect(items.toSet())
        assertTrue("Expected Iterator members, but got: $items", intersection.isNotEmpty())
    }

    fun test_IteratorAfterLines_WithPrefix() {
        val code = """
            import lyng.io.fs
            import lyng.stdlib

            val files = Path("../..").lines().t<caret>
        """.trimIndent()
        val imported = listOf("lyng.io.fs", "lyng.stdlib")
        ensureDocs(imported)

        val items = complete(code)
        // Must not propose globals after dot even with prefix
        assertFalse(items.contains("Path"))
        assertFalse(items.contains("Array"))
        assertFalse(items.contains("String"))

        // All suggestions should start with the typed prefix (case-insensitive)
        assertTrue(items.all { it.startsWith("t", ignoreCase = true) })

        // Some Iterator member starting with 't' should be present (e.g., toList)
        val expected = aggregateMemberNames("Iterator", imported).filter { it.startsWith("t", true) }.toSet()
        if (expected.isNotEmpty()) {
            val intersection = expected.intersect(items.toSet())
            assertTrue("Expected Iterator members with prefix 't', got: $items", intersection.isNotEmpty())
        } else {
            // If registry has no 't*' members, at least suggestions should not be empty
            assertTrue(items.isNotEmpty())
        }
    }

    fun test_ListLiteral_MembersWithInherited() {
        val code = """
            import lyng.stdlib

            val x = [1,2,3].<caret>
        """.trimIndent()
        val imported = listOf("lyng.stdlib")
        ensureDocs(imported)

        val items = complete(code)
        // Must not propose globals after dot
        assertFalse(items.contains("Array"))
        assertFalse(items.contains("String"))
        assertFalse(items.contains("Path"))

        // Expect members from List plus parents (Collection/Iterable)
        val expected = aggregateMemberNames("List", imported)
        val intersection = expected.intersect(items.toSet())
        assertTrue("Expected List/Collection/Iterable members, got: $items", intersection.isNotEmpty())

        // Heuristic: we expect more than a couple of items (not just size/toList)
        assertTrue("Too few member suggestions after list literal: $items", items.size >= 3)
    }

    fun test_ProcessModule_Completion() {
        val code = """
            import lyng.io.process
            Process.<caret>
        """.trimIndent()
        val imported = listOf("lyng.io.process")
        ensureDocs(imported)

        val items = complete(code)
        assertTrue("Should contain 'execute'", items.contains("execute"))
        assertTrue("Should contain 'shell'", items.contains("shell"))
    }

    fun test_RunningProcess_Completion() {
        val code = """
            import lyng.io.process
            val p = Process.shell("ls")
            p.<caret>
        """.trimIndent()
        val imported = listOf("lyng.io.process")
        ensureDocs(imported)

        val items = complete(code)
        assertTrue("Should contain 'stdout'", items.contains("stdout"))
        assertTrue("Should contain 'waitFor'", items.contains("waitFor"))
        assertTrue("Should contain 'signal'", items.contains("signal"))
    }

    fun test_RegistryDirect() {
        DocsBootstrap.ensure()
        val docs = BuiltinDocRegistry.docsForModule("lyng.io.process")
        assertTrue("Docs for lyng.io.process should not be empty", docs.isNotEmpty())
        val processClass = docs.filterIsInstance<MiniClassDecl>().firstOrNull { it.name == "Process" }
        assertNotNull("Should contain Process class", processClass)
        assertTrue("Process should have members", processClass!!.members.isNotEmpty())
    }
}
