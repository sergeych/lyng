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
}
