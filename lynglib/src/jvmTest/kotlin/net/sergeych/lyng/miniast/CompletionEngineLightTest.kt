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

package net.sergeych.lyng.miniast

import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Ignore("TODO(bytecode-only): uses fallback")
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
    fun stringLiteral_re_hasReturnTypeRegex() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib")
        val code = """
            import lyng.stdlib

            val s = "abc".<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val reItem = items.firstOrNull { it.name == "re" }
        assertNotNull(reItem, "Expected to find 're' in String members, got: ${items.map { it.name }}")
        // Type text should contain ": Regex"
        assertTrue(reItem.typeText?.contains("Regex") == true, "Expected type text to contain 'Regex', was: ${reItem.typeText}")
    }

    @Test
    fun stringLiteral_parenthesized_re_hasReturnTypeRegex() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib")
        val code = """
            // No imports on purpose; stdlib must still be available

            val s = ("abc").<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val names = items.map { it.name }
        assertTrue(names.isNotEmpty(), "Expected String members for parenthesized literal, got empty list")
        val reItem = items.firstOrNull { it.name == "re" }
        assertNotNull(reItem, "Expected to find 're' for parenthesized String literal, got: $names")
        assertTrue(reItem.typeText?.contains("Regex") == true, "Expected ': Regex' for re(), was: ${reItem.typeText}")
    }

    @Test
    fun stringLiteral_noImports_stillHasStringMembers() = runBlocking {
        TestDocsBootstrap.ensure("lyng.stdlib")
        val code = """
            val s = "super".<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val names = items.map { it.name }
        assertTrue(names.isNotEmpty(), "Expected String members without explicit imports, got empty list")
        val reItem = items.firstOrNull { it.name == "re" }
        assertNotNull(reItem, "Expected to find 're' without explicit imports, got: $names")
        assertTrue(reItem.typeText?.contains("Regex") == true, "Expected ': Regex' for re() without imports, was: ${reItem.typeText}")
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

    @Test
    fun constructorParametersInMethod() = runBlocking {
        val code = """
            class MyClass(myParam) {
                fun myMethod() {
                    myp<caret>
                }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("myParam"), "Constructor parameter 'myParam' should be proposed, but got: $ns")
    }

    @Test
    fun classFieldsInMethod() = runBlocking {
        val code = """
            class MyClass {
                val myField = 1
                fun myMethod() {
                    myf<caret>
                }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("myField"), "Class field 'myField' should be proposed, but got: $ns")
    }

    @Test
    fun inferredTypeFromFunctionCall() = runBlocking {
        val code = """
            extern fun test(a: Int): List<Int>
            val x = test(1)
            val y = x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for inferred List type, but got: $ns")
    }

    @Test
    fun inferredTypeFromMemberCall() = runBlocking {
        val code = """
            extern class MyClass {
                fun getList(): List<String>
            }
            extern val c: MyClass
            val x = c.getList()
            val y = x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for inferred List type from member call, but got: $ns")
    }

    @Test
    fun inferredTypeFromListLiteral() = runBlocking {
        val code = """
            val x = [1, 2, 3]
            val y = x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for inferred List type from literal, but got: $ns")
    }

    @Test
    fun inferredTypeAfterIndexing() = runBlocking {
        val code = """
            extern fun test(): List<String>
            val x = test()
            val y = x[0].<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // Should contain String members, e.g., 'length' or 're'
        assertTrue(ns.contains("length"), "String member 'length' should be suggested after indexing List<String>, but got: $ns")
        assertTrue(ns.contains("re"), "String member 're' should be suggested after indexing List<String>, but got: $ns")
    }

    @Test
    fun inferredTypeFromAssignmentWithoutVal() = runBlocking {
        val code = """
            extern fun test(): List<String>
            x = test()
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for variable assigned without 'val', but got: $ns")
        assertTrue(ns.contains("add"), "List member 'add' should be suggested for variable assigned without 'val', but got: $ns")
    }

    @Test
    fun inferredTypeAfterIndexingWithoutVal() = runBlocking {
        val code = """
            extern fun test(): List<String>
            x = test()
            x[0].<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // String members include 'trim', 'lower', etc.
        assertTrue(ns.contains("trim"), "String member 'trim' should be suggested for x[0] where x assigned without val, but got: $ns")
        assertFalse(ns.contains("add"), "List member 'add' should NOT be suggested for x[0], but got: $ns")
    }

    @Test
    fun transitiveInferenceWithoutVal() = runBlocking {
        val code = """
            extern fun test(): List<String>
            x = test()
            y = x
            y.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for transitive inference, but got: $ns")
    }

    @Test
    fun objectMemberReturnInference() = runBlocking {
        val code = """
            object O {
                fun getList(): List<String> = []
            }
            O.getList().<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for object member call, but got: $ns")
    }

    @Test
    fun directFunctionCallCompletion() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            test(1).<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for direct function call, but got: $ns")
        assertTrue(ns.contains("map"), "Inherited member 'map' should be suggested for List, but got: $ns")
    }

    @Test
    fun completionWithTrailingDotError() = runBlocking {
        // This simulates typing mid-expression where the script is technically invalid
        val code = """
            extern fun test(): List<String>
            test().<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested even if script ends with a dot, but got: $ns")
    }

    @Test
    fun listLiteralCompletion() = runBlocking {
        val code = "[].<caret>"
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for [], but got: $ns")
        assertTrue(ns.contains("map"), "Inherited member 'map' should be suggested for [], but got: $ns")
    }

    @Test
    fun userReportedSample() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            x = test(1)
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for x, but got: $ns")
    }

    @Test
    fun userReportedSampleIndexed() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            x = test(1)
            x[0].<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "String member 'size' should be suggested for x[0], but got: $ns")
        assertTrue(ns.contains("trim"), "String member 'trim' should be suggested for x[0], but got: $ns")
    }

    @Test
    fun userReportedSampleImplicitVariable() = runBlocking {
        val code = """
            extern fun test(): List<String>
            x = test()
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for implicit variable x, but got: $ns")
    }

    @Test
    fun userReportedSampleNoDot() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            x = test(1)
            x[0]<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("x"), "Implicit variable 'x' should be suggested as global, but got: $ns")
    }

    @Test
    fun userReportedIssue_X_equals_test2() = runBlocking {
        val code = """
            extern fun test2(): List<String>
            x = test2
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // Since test2 is a function, x = test2 (without parens) should probably be the function itself,
        // but current DocLookupUtils returns returnType.
        // If it returns List<String>, then size should be there.
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for x = test2, but got: $ns")
    }

    @Test
    fun anyMembersOnInferred() = runBlocking {
        val code = """
            x = 42
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("toString"), "Any member 'toString' should be suggested for x=42, but got: $ns")
        assertTrue(ns.contains("let"), "Any member 'let' should be suggested for x=42, but got: $ns")
    }

    @Test
    fun charMembersOnIndexedString() = runBlocking {
        val code = """
            x = "hello"
            x[0].<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("code"), "Char member 'code' should be suggested for indexed string x[0], but got: $ns")
        assertTrue(ns.contains("toString"), "Any member 'toString' should be suggested for x[0], but got: $ns")
    }

    @Test
    fun extensionMemberOnInferredList() = runBlocking {
        val code = """
            extern fun getNames(): List<String>
            ns = getNames()
            ns.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("map"), "Extension member 'map' should be suggested for List, but got: $ns")
        assertTrue(ns.contains("filter"), "Extension member 'filter' should be suggested for List, but got: $ns")
    }

    @Test
    fun inferredTypeFromExternFunWithVal() = runBlocking {
        val code = """
            extern fun test(a: Int): List<Int>
            val x = test(1)
            x.<caret>
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for val x = test(1), but got: $ns")
    }

    @Test
    fun userReportedNestedSample() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            class X(fld1, fld2) {
                var prop 
                    get() { 12 }
                    set(value) {
                        val x = test(2)
                        x.<caret>
                    }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "List member 'size' should be suggested for local val x inside set(), but got: $ns")
    }

    @Test
    fun userReportedNestedSampleIndexed() = runBlocking {
        val code = """
            extern fun test(value: Int): List<String>
            class X(fld1, fld2) {
                var prop 
                    get() { 12 }
                    set(value) {
                        val x = test(2)
                        x[0].<caret>
                    }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("size"), "String member 'size' should be suggested for local x[0] inside set(), but got: $ns")
    }

    @Test
    fun functionArgumentsInBody() = runBlocking {
        val code = """
            fun test(myArg1, myArg2) {
                myA<caret>
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("myArg1"), "Function argument 'myArg1' should be proposed, but got: $ns")
        assertTrue(ns.contains("myArg2"), "Function argument 'myArg2' should be proposed, but got: $ns")
    }

    @Test
    fun methodArgumentsInBody() = runBlocking {
        val code = """
            class MyClass {
                fun test(myArg1, myArg2) {
                    myA<caret>
                }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        assertTrue(ns.contains("myArg1"), "Method argument 'myArg1' should be proposed, but got: $ns")
        assertTrue(ns.contains("myArg2"), "Method argument 'myArg2' should be proposed, but got: $ns")
    }

    @Test
    fun nestedShadowingCompletion() = runBlocking {
        val code = """
            val x = 42
            class X {
                fun test() {
                    val x = "hello"
                    x.<caret>
                }
            }
        """.trimIndent()
        val items = CompletionEngineLight.completeAtMarkerSuspend(code)
        val ns = names(items)
        // Should contain String members (like trim)
        assertTrue(ns.contains("trim"), "String member 'trim' should be suggested for shadowed x, but got: $ns")
    }
}
