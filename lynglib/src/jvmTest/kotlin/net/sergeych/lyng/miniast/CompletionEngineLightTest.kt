package net.sergeych.lyng.miniast

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionEngineLightTest {

    private fun names(items: List<CompletionItem>): List<String> = items.map { it.name }

    @Test
    fun iteratorAfterLines_noGlobals() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib", "lyng.io.fs")
        val code = """
            import lyng.io.fs
            import lyng.stdlib

            val files = Path("../..").lines().<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // No globals after a dot
        assertFalse(ns.contains("Path"), "Should not contain global 'Path' after dot")
        assertFalse(ns.contains("Array"), "Should not contain global 'Array' after dot")
        assertFalse(ns.contains("String"), "Should not contain global 'String' after dot")
        // Should have some iterator members (at least non-empty)
        assertTrue(ns.isNotEmpty(), "Iterator members should be suggested")
    }

    @Test
    fun iteratorAfterLines_withPrefix() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib", "lyng.io.fs")
        val code = """
            import lyng.io.fs
            import lyng.stdlib

            val files = Path("../..").lines().t<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // No globals after a dot even with prefix
        assertFalse(ns.contains("Path"))
        assertFalse(ns.contains("Array"))
        assertFalse(ns.contains("String"))
        // All start with 't'
        assertTrue(ns.all { it.startsWith("t", ignoreCase = true) }, "All suggestions should respect prefix 't'")
    }

    @Test
    fun listLiteral_membersAndInherited() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib")
        val code = """
            import lyng.stdlib

            val x = [1,2,3].<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // No globals after a dot
        assertFalse(ns.contains("Array"))
        assertFalse(ns.contains("String"))
        assertFalse(ns.contains("Path"))
        // Expect more than a couple of items (not just one)
        assertTrue(ns.size >= 3, "Too few member suggestions after list literal: $ns")
    }

    @Test
    fun stringLiteral_members() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib")
        val code = """
            import lyng.stdlib

            val s = "abc".<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.isNotEmpty(), "String members should be suggested")
        assertFalse(ns.contains("Path"))
    }

    @Test
    fun shebang_and_fs_import_iterator_after_lines() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib", "lyng.io.fs")
        val code = """
            #!/bin/env lyng

            import lyng.io.fs
            import lyng.stdlib

            val files = Path("../..").lines().<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // Should not contain globals after dot
        assertFalse(ns.contains("Path"))
        assertFalse(ns.contains("Array"))
        assertFalse(ns.contains("String"))
        // Should contain some iterator members
        assertTrue(ns.isNotEmpty(), "Iterator members should be suggested after lines() with shebang present")
    }
}
