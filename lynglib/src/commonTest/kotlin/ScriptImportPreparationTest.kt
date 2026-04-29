/*
 * Copyright 2026 Sergey S. Chernov
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
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.EvalSession
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.toInt
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ScriptImportPreparationTest {

    private fun nestedImportSources(prefix: String): Array<String> =
        arrayOf(
            """
            package $prefix.alpha

            class Alpha {
                val headers = Map<String, String>()

                fun tagged(port: Int, host: String): String {
                    val task: Deferred = launch {
                        host + ":" + port + ":" + headers.size
                    }
                    return task.await()
                }
            }

            fun alphaValue() = Alpha().tagged(7, "alpha")
            """.trimIndent(),
            """
            package $prefix.beta
            import $prefix.alpha

            fun betaValue() = alphaValue() + "|" + Alpha().tagged(8, "beta")
            """.trimIndent(),
            """
            package $prefix.gamma
            import $prefix.alpha
            import $prefix.beta

            val String.gammaTag get() = this + ":gamma"

            fun gammaValue() = betaValue() + "|" + "done".gammaTag
            """.trimIndent()
        )

    private fun nestedImportManager(prefix: String = "tree"): ImportManager =
        Script.defaultImportManager.copy().apply {
            addTextPackages(*nestedImportSources(prefix))
        }

    @Test
    fun scriptImportIntoExplicitlyPreparesExistingScope() = runTest {
        val manager = ImportManager()
        manager.addTextPackages(
            """
            package foo

            val answer = 42
            """.trimIndent()
        )
        val script = Compiler.compile(
            Source(
                "<prepare-scope>",
                """
                import foo
                answer
                """.trimIndent()
            ),
            manager
        )
        val scope = manager.newModule()

        assertNull(scope["answer"])

        script.importInto(scope)

        val record = assertNotNull(scope["answer"])
        assertEquals(42, scope.resolve(record, "answer").toInt())
    }

    @Test
    fun scriptInstantiateModuleUsesSeedScopeImportProvider() = runTest {
        val manager = ImportManager()
        manager.addTextPackages(
            """
            package foo

            val answer = 42
            """.trimIndent()
        )
        val script = Compiler.compile(
            Source(
                "<instantiate-module>",
                """
                import foo
                answer
                """.trimIndent()
            ),
            manager
        )
        val seedScope = manager.newModule()

        val module = script.instantiateModule(seedScope)

        val record = assertNotNull(module["answer"])
        assertEquals(42, module.resolve(record, "answer").toInt())
    }

    @Test
    fun repeatedImportIntoOnSameScopeIsIdempotentForNestedPackageGraph() = runTest {
        val manager = nestedImportManager()
        val script = Compiler.compile(
            Source(
                "<repeat-import-into>",
                """
                import tree.gamma
                import tree.beta
                import tree.alpha

                gammaValue()
                """.trimIndent()
            ),
            manager
        )
        val scope = manager.newStdScope()

        script.importInto(scope)
        val importedGammaValue = assertNotNull(scope["gammaValue"])
        val importedAlpha = assertNotNull(scope["Alpha"])

        repeat(5) {
            script.importInto(scope)
        }

        assertSame(importedGammaValue, scope["gammaValue"])
        assertSame(importedAlpha, scope["Alpha"])
        assertEquals(
            "alpha:7:0|beta:8:0|done:gamma",
            (script.execute(scope) as ObjString).value
        )
    }

    @Test
    fun repeatedEvalOnSameSessionCanReimportNestedPackageGraph() = runTest {
        val prefix = "repeattree"
        val manager = nestedImportManager(prefix)
        val scope = manager.newModule()
        val session = EvalSession(scope)

        try {
            repeat(5) { index ->
                val result = session.eval(
                    Source(
                        "<repeat-eval-$index>",
                        """
                        import $prefix.gamma
                        import $prefix.beta
                        import $prefix.alpha

                        gammaValue()
                        """.trimIndent()
                    )
                ) as ObjString

                assertEquals("alpha:7:0|beta:8:0|done:gamma", result.value)
            }
        } finally {
            session.cancelAndJoin()
        }
    }

    @Test
    fun importedContextReceiverExtensionIsAvailableInReceiverDsl() = runTest {
        val manager = Script.defaultImportManager.copy().apply {
            addTextPackages(
                """
                package imported.ctxdsl

                class Tag(name: String) {
                    val name = name
                    var inner = ""

                    fun child(tagName: String, block: Tag.()->void) {
                        val child = Tag(tagName)
                        child.apply { block(this) }
                        inner += child.render()
                    }

                    fun h3(block: Tag.()->void) { child("h3", block) }
                    fun addText(text: String) { inner += text }
                    fun render() = "<" + name + ">" + inner + "</" + name + ">"
                }

                context(Tag)
                fun String.unaryPlus() {
                    this@Tag.addText(this)
                }
                """.trimIndent()
            )
        }
        val script = Compiler.compile(
            Source(
                "<ctx-dsl-import>",
                """
                import imported.ctxdsl

                fun html(block: Tag.()->void) {
                    val root = Tag("html")
                    root.apply { block(this) }
                    root.render()
                }

                val page = html {
                    h3 {
                        +"Imported"
                    }
                }

                assertEquals("<html><h3>Imported</h3></html>", page)
                assertEquals("plain", +"plain")
                page
                """.trimIndent()
            ),
            manager
        )

        val result = script.execute(manager.newStdScope()) as ObjString
        assertEquals("<html><h3>Imported</h3></html>", result.value)
    }
}
