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
 *
 */

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.resolution.CompileTimeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompileTimeResolutionDryRunTest {

    @Test
    fun dryRunReturnsMetadataContainer() = runTest {
        val report = CompileTimeResolver.dryRun(
            Source("<dry-run>", "val x = 1"),
            Script.defaultImportManager
        )
        assertEquals("<dry-run>", report.moduleName)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun compilerDryRunEntryPoint() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "val x = 1"),
            Script.defaultImportManager
        )
        assertEquals("<dry-run>", report.moduleName)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunCollectsModuleSymbols() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "val x = 1\nfun f() { x }\nclass C"),
            Script.defaultImportManager
        )
        val names = report.symbols.map { it.name }.toSet()
        assertTrue("x" in names)
        assertTrue("f" in names)
        assertTrue("C" in names)
    }

    @Test
    fun dryRunCollectsObjectSymbols() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "object O { val x = 1 }\nO"),
            Script.defaultImportManager
        )
        val names = report.symbols.map { it.name }.toSet()
        assertTrue("O" in names)
    }

    @Test
    fun dryRunCollectsCtorParams() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "class C(x) { val y = x }"),
            Script.defaultImportManager
        )
        val names = report.symbols.map { it.name }.toSet()
        assertTrue("x" in names)
    }

    @Test
    fun dryRunCollectsMapLiteralShorthandRefs() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "val x = 1\nval m = { x: }\nm"),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunCollectsBaseClassRefs() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "class A {}\nclass B : A {}"),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunCollectsTypeRefs() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "class A {}\nval x: A = A()"),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunAcceptsQualifiedTypeRefs() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "val x: lyng.time.Instant? = null"),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunCollectsExtensionReceiverRefs() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "class A {}\nfun A.foo() = 1\nval A.bar get() = 2"),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunAcceptsLoopAndCatchLocals() = runTest {
        val report = Compiler.dryRun(
            Source(
                "<dry-run>",
                """
                fun f() {
                    for (i in 0..2) { i }
                    try { 1 } catch(e: Exception) { e }
                }
                """.trimIndent()
            ),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun dryRunCollectsCaptures() = runTest {
        val report = Compiler.dryRun(
            Source("<dry-run>", "val x = 1\nval f = { x }\nf()"),
            Script.defaultImportManager
        )
        val captureNames = report.captures.map { it.name }.toSet()
        assertTrue("x" in captureNames)
    }

    @Test
    fun dryRunAcceptsScopeReflectionHelpers() = runTest {
        val report = Compiler.dryRun(
            Source(
                "<dry-run>",
                """
                fun f() {
                    var x = 1
                    scope.get("x")
                    scope.set("x", 2)
                    scope.locals()
                    scope.captures()
                    scope.members()
                }
                f()
                """.trimIndent()
            ),
            Script.defaultImportManager
        )
        assertTrue(report.errors.isEmpty())
    }
}
