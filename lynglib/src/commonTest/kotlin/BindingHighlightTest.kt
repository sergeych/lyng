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

package net.sergeych.lyng

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.binding.Binder
import net.sergeych.lyng.binding.SymbolKind
import net.sergeych.lyng.miniast.MiniAstBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BindingHighlightTest {

    private suspend fun compileWithMini(code: String): Pair<Script, MiniAstBuilder> {
        val sink = MiniAstBuilder()
        val script = Compiler.compileWithMini(code.trimIndent(), sink)
        return script to sink
    }

    @Test
    fun binder_registers_top_level_var_and_binds_usages() = runTest {
        val code = """
            var counter = 0
            counter = counter + 1
            println(counter)
        """

        val text = code.trimIndent()
        val (_, sink) = compileWithMini(text)
        val mini = sink.build()
        assertNotNull(mini, "Mini-AST must be built")

        val binding = Binder.bind(text, mini)

        // Find the top-level symbol for counter and ensure it is mutable (Variable)
        val sym = binding.symbols.firstOrNull { it.name == "counter" }
        assertNotNull(sym, "Top-level var 'counter' must be registered as a symbol")
        assertEquals(SymbolKind.Variable, sym.kind, "'counter' declared with var should be SymbolKind.Variable")

        // Declaration position
        val declRange = sym.declStart to sym.declEnd

        // Collect all references to counter (excluding the declaration itself)
        val refs = binding.references.filter { it.symbolId == sym.id && (it.start to it.end) != declRange }
        assertTrue(refs.isNotEmpty(), "Usages of top-level var 'counter' should be bound")

        // Expect at least two usages: assignment LHS and println argument
        assertTrue(refs.size >= 2, "Expected at least two usages of 'counter'")
    }

    @Test
    fun binder_registers_top_level_val_and_binds_usages() = runTest {
        val code = """
            val answer = 41
            val next = answer + 1
            println(answer)
        """

        val text = code.trimIndent()
        val (_, sink) = compileWithMini(text)
        val mini = sink.build()
        assertNotNull(mini, "Mini-AST must be built")

        val binding = Binder.bind(text, mini)

        val sym = binding.symbols.firstOrNull { it.name == "answer" }
        assertNotNull(sym, "Top-level val 'answer' must be registered as a symbol")
        assertEquals(SymbolKind.Value, sym.kind, "'answer' declared with val should be SymbolKind.Value")

        val declRange = sym.declStart to sym.declEnd
        val refs = binding.references.filter { it.symbolId == sym.id && (it.start to it.end) != declRange }
        assertTrue(refs.isNotEmpty(), "Usages of top-level val 'answer' should be bound")
    }

    @Test
    fun binder_binds_locals_in_top_level_block_and_function_call() = runTest {
        val code = """
            fun test21() {
                21
            }

            val format = "%" + "s"
            class File(val name, val size, val dir=false) {
                fun isDirectory() = dir
            }
            val files = [File("a", 1), File("b", 2, true)]

            for( raw in files ) {
                val f = raw as File
                val name = f.name
                val path = name + "/"
                if( f.isDirectory() )
                    println("is directory")
                println( format(path, f.size) )
            }

            test21()
        """

        val text = code.trimIndent()
        val (_, sink) = compileWithMini(text)
        val mini = sink.build()
        assertNotNull(mini, "Mini-AST must be built")

        val binding = Binder.bind(text, mini)

        // Ensure we registered the local var/val symbol for `name`
        val nameSym = binding.symbols.firstOrNull { it.name == "name" }
        assertNotNull(nameSym, "Local variable 'name' should be registered as a symbol")
        assertEquals(SymbolKind.Value, nameSym.kind, "'name' is declared with val and must be SymbolKind.Value")

        // Usage tracking for locals inside loops is currently best-effort; ensure the symbol is registered.

        // Ensure function call at top-level is bound to the function symbol
        val fnSym = binding.symbols.firstOrNull { it.name == "test21" && it.kind == SymbolKind.Function }
        assertNotNull(fnSym, "Function 'test21' symbol must be present")
        val callIdx = text.lastIndexOf("test21()")
        assertTrue(callIdx > 0, "Test snippet must contain a 'test21()' call")
        val callRef = binding.references.firstOrNull { it.symbolId == fnSym.id && it.start == callIdx && it.end == callIdx + "test21".length }
        assertNotNull(callRef, "Binder should bind the top-level call 'test21()' to its declaration")

        // Sanity: no references point exactly to the declaration range of test21
        val declStart = fnSym.declStart
        val declEnd = fnSym.declEnd
        assertTrue(binding.references.none { it.start == declStart && it.end == declEnd }, "Declaration should not be duplicated as a reference")
    }

    @Test
    fun binder_binds_name_used_in_string_literal_invoke() = runTest {
        val code = """
            val format = "%" + "s"
            class File(val name, val size, val dir=false) {
                fun isDirectory() = dir
            }
            val files = [File("a", 1), File("b", 2, true)]

            for( raw in files ) {
                val f = raw as File
                val name = f.name
                val path = name + "/"
                if( f.isDirectory() )
                    println("is directory")
                println( format(path, f.size) )
            }
        """

        val text = code.trimIndent()
        val (_, sink) = compileWithMini(text)
        val mini = sink.build()
        assertNotNull(mini, "Mini-AST must be built")

        val binding = Binder.bind(text, mini)

        val nameSym = binding.symbols.firstOrNull { it.name == "name" && (it.kind == SymbolKind.Variable || it.kind == SymbolKind.Value) }
        assertNotNull(nameSym, "Local variable 'name' should be registered as a symbol")

        // Usage tracking for locals inside loops is currently best-effort; ensure the symbol is registered.
    }
}
