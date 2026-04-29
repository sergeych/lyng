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

package net.sergeych.lyng.io.html

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.pacman.ImportManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyngHtmlModuleTest {

    @Test
    fun testModuleRegistrationIsIdempotent() = runTest {
        val importManager = ImportManager()
        assertTrue(createHtmlModule(importManager))
        assertFalse(createHtmlModule(importManager))
    }

    @Test
    fun testModuleCanBeImported() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-test>",
                """
                import lyng.io.html
                42
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals("42", result.inspect(scope))
    }

    @Test
    fun testHtmlDslBuildsNestedDocument() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-dsl-test>",
                """
                import lyng.io.html

                html {
                    head {
                        title { +"Demo" }
                    }
                    body {
                        h3 { +"Heading 3" }
                        p {
                            attr("data-x", "\"quoted\" & <tag>")
                            +"Text & <more>"
                        }
                    }
                }
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals(
            "<!doctype html><html><head><title>Demo</title></head><body><h3>Heading 3</h3><p data-x=\"&quot;quoted&quot; &amp; &lt;tag&gt;\">Text &amp; &lt;more&gt;</p></body></html>",
            (result as ObjString).value
        )
    }

    @Test
    fun testHtmlDslSupportsRawAndVoidTags() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-void-test>",
                """
                import lyng.io.html

                html {
                    head {
                        meta { attr("charset", "utf-8") }
                    }
                    body {
                        div {
                            id("root")
                            classes("app shell")
                            raw("<span>trusted</span>")
                            br {}
                        }
                    }
                }
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals(
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><div id=\"root\" class=\"app shell\"><span>trusted</span><br></div></body></html>",
            (result as ObjString).value
        )
    }

    @Test
    fun testHtmlDslTypedAttributeHelpers() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-typed-attrs-test>",
                """
                import lyng.io.html

                html {
                    head {
                        metaCharset()
                        stylesheet("/site.css")
                    }
                    body {
                        nav {
                            a(href: "/home") { +"Home" }
                        }
                        img(src: "/logo.png", alt: "Logo & mark")
                        input(type: "hidden", name: "token", value: "\"abc\"")
                    }
                }
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals(
            "<!doctype html><html><head><meta charset=\"utf-8\"><link rel=\"stylesheet\" href=\"/site.css\"></head><body><nav><a href=\"/home\">Home</a></nav><img src=\"/logo.png\" alt=\"Logo &amp; mark\"><input type=\"hidden\" name=\"token\" value=\"&quot;abc&quot;\"></body></html>",
            (result as ObjString).value
        )
    }

    @Test
    fun testHtmlDslGenericTagsAndFlagAttributes() = runTest {
        val scope = Script.newScope()
        createHtmlModule(scope.importManager)

        val result = Compiler.compile(
            Source(
                "<html-generic-tag-test>",
                """
                import lyng.io.html

                html {
                    body {
                        tag("custom-element") {
                            flag("hidden")
                            +"Secret"
                        }
                        voidTag("source") {
                            attr("srcset", "/image.webp")
                            attr("type", "image/webp")
                        }
                    }
                }
                """.trimIndent()
            ),
            scope.importManager
        ).execute(scope)

        assertEquals(
            "<!doctype html><html><body><custom-element hidden>Secret</custom-element><source srcset=\"/image.webp\" type=\"image/webp\"></body></html>",
            (result as ObjString).value
        )
    }
}
