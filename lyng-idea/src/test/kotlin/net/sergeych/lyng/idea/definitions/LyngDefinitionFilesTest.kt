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

package net.sergeych.lyng.idea.definitions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.idea.docs.LyngDocumentationProvider
import net.sergeych.lyng.idea.navigation.LyngPsiReference
import net.sergeych.lyng.idea.settings.LyngFormatterSettings
import net.sergeych.lyng.idea.util.LyngAstManager
import net.sergeych.lyng.miniast.CompletionEngineLight

class LyngDefinitionFilesTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = ""

    private fun enableCompletion() {
        LyngFormatterSettings.getInstance(project).enableLyngCompletionExperimental = true
    }

    private fun addDefinitionsFile() {
        val defs = """
            /** Utilities exposed via .lyng.d */
            class Declared(val name: String) {
                /** Size property */
                val size: Int = 0
                
                /** Returns greeting. */
                fun greet(who: String): String = "hi " + who
            }
            
            /** Top-level function. */
            fun topFun(x: Int): Int = x + 1
        """.trimIndent()
        myFixture.addFileToProject("api.lyng.d", defs)
    }

    fun test_CompletionsIncludeDefinitions() {
        addDefinitionsFile()
        enableCompletion()
        run {
            val code = """
                val v = top<caret>
            """.trimIndent()
            myFixture.configureByText("main.lyng", code)
            val text = myFixture.editor.document.text
            val caret = myFixture.caretOffset
            val analysis = LyngAstManager.getAnalysis(myFixture.file)
            val engine = runBlocking { CompletionEngineLight.completeSuspend(text, caret, analysis?.mini, analysis?.binding).map { it.name } }
            assertTrue("Expected topFun from .lyng.d; got=$engine", engine.contains("topFun"))
        }
        run {
            val code = """
                <caret>
            """.trimIndent()
            myFixture.configureByText("other.lyng", code)
            val text = myFixture.editor.document.text
            val caret = myFixture.caretOffset
            val analysis = LyngAstManager.getAnalysis(myFixture.file)
            val engine = runBlocking { CompletionEngineLight.completeSuspend(text, caret, analysis?.mini, analysis?.binding).map { it.name } }
            assertTrue("Expected Declared from .lyng.d; got=$engine", engine.contains("Declared"))
        }
    }

    fun test_GotoDefinitionResolvesToDefinitionFile() {
        addDefinitionsFile()
        val code = """
            val x = topFun(1)
            val y = Declared("x")
            y.gre<caret>et("me")
        """.trimIndent()
        myFixture.configureByText("main.lyng", code)
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset) ?: myFixture.file.findElementAt((offset - 1).coerceAtLeast(0))
        assertNotNull("Expected element at caret for resolve", element)
        val ref = LyngPsiReference(element!!)
        val resolved = ref.resolve()
        assertNotNull("Expected reference to resolve", resolved)
        assertTrue("Expected .lyng.d target; got=${resolved!!.containingFile.name}", resolved.containingFile.name.endsWith(".lyng.d"))
    }

    fun test_QuickDocUsesDefinitionDocs() {
        addDefinitionsFile()
        val code = """
            val y = Declared("x")
            y.gre<caret>et("me")
        """.trimIndent()
        myFixture.configureByText("main.lyng", code)
        val provider = LyngDocumentationProvider()
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset) ?: myFixture.file.findElementAt((offset - 1).coerceAtLeast(0))
        assertNotNull("Expected element at caret for doc", element)
        val doc = provider.generateDoc(element, element)
        assertNotNull("Expected Quick Doc", doc)
        assertTrue("Doc should include summary; got=$doc", doc!!.contains("Returns greeting"))
    }

    fun test_DiagnosticsIgnoreDefinitionSymbols() {
        addDefinitionsFile()
        val code = """
            val x = topFun(1)
            val y = Declared("x")
            y.greet("me")
        """.trimIndent()
        myFixture.configureByText("main.lyng", code)
        val analysis = LyngAstManager.getAnalysis(myFixture.file)
        val messages = analysis?.diagnostics?.map { it.message } ?: emptyList()
        assertTrue("Should not report unresolved name for topFun", messages.none { it.contains("unresolved name: topFun") })
        assertTrue("Should not report unresolved name for Declared", messages.none { it.contains("unresolved name: Declared") })
        assertTrue("Should not report unresolved member for greet", messages.none { it.contains("unresolved member: greet") })
    }

    fun test_DiagnosticsDoNotReportVoidAsUnresolvedName() {
        val code = """
            fun f(): void {
                return void
            }
        """.trimIndent()
        myFixture.configureByText("main.lyng", code)
        val analysis = LyngAstManager.getAnalysis(myFixture.file)
        val messages = analysis?.diagnostics?.map { it.message } ?: emptyList()
        assertTrue("Should not report unresolved name for void, got=$messages", messages.none { it.contains("unresolved name: void") })
    }
}
