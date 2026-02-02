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

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.resolution.SymbolOrigin
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompileTimeResolutionSpecTest {

    private suspend fun dryRun(code: String) =
        Compiler.dryRun(Source("<dry-run>", code.trimIndent()), Script.defaultImportManager)

    @Test
    fun resolvesLocalsBeforeMembers() = runTest {
        val report = dryRun(
            """
            class C {
                val x = 1
                fun f() { val x = 2; x }
            }
            """
        )
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.any { it.message.contains("shadowing member: x") })
    }

    @Test
    fun capturesOuterLocalsDeterministically() = runTest {
        val report = dryRun(
            """
            var g = 1
            fun f() {
                var g = 2
                val h = { g }
                h()
            }
            """
        )
        assertTrue(report.errors.isEmpty())
        assertTrue(report.captures.any { it.name == "g" && it.origin == SymbolOrigin.OUTER })
    }

    @Test
    fun capturesModuleGlobalsAsOuterScope() = runTest {
        val report = dryRun(
            """
            val G = 10
            fun f(x=0) = x + G
            """
        )
        assertTrue(report.errors.isEmpty())
        assertTrue(report.captures.any { it.name == "G" && it.origin == SymbolOrigin.MODULE })
    }

    @Test
    fun unresolvedNameIsCompileError() = runTest {
        val report = dryRun(
            """
            fun f() { missingName }
            f()
            """
        )
        assertTrue(report.errors.any { it.message.contains("missingName") })
    }

    @Test
    fun miAmbiguityIsCompileError() = runTest {
        val report = dryRun(
            """
            class A { fun foo() = 1 }
            class B { fun foo() = 2 }
            class C : A, B { }
            C().foo()
            """
        )
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun miOverrideResolvesConflict() = runTest {
        val report = dryRun(
            """
            class A { fun foo() = 1 }
            class B { fun foo() = 2 }
            class C : A, B {
                override fun foo() = 3
            }
            C().foo()
            """
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun qualifiedThisMemberAccess() = runTest {
        val report = dryRun(
            """
            class A { fun foo() = 1 }
            class B { fun foo() = 2 }
            class C : A, B {
                override fun foo() = 3
                fun aFoo() = this@A.foo()
                fun bFoo() = this@B.foo()
            }
            val c = C()
            c.aFoo()
            c.bFoo()
            """
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun reflectionIsExplicitOnly() = runTest {
        val report = dryRun(
            """
            fun f() {
                val x = 1
                scope.get("x")
            }
            f()
            """
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun memberShadowingAllowedWithWarning() = runTest {
        val report = dryRun(
            """
            class C {
                val x = 1
                fun f() { val x = 2; x }
            }
            """
        )
        assertTrue(report.errors.isEmpty())
        assertTrue(report.warnings.any { it.message.contains("shadowing member: x") })
    }

    @Test
    fun parameterShadowingAllowed() = runTest {
        val report = dryRun(
            """
            fun f(a=0) {
                var a = a * 10
                a
            }
            """
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun shadowingCaptureIsAllowed() = runTest {
        val report = dryRun(
            """
            fun outer() {
                var x = 1
                fun inner() {
                    val x = 2
                    val c = { x }
                    c()
                }
                inner()
            }
            """
        )
        assertTrue(report.errors.isEmpty())
        assertTrue(report.captures.any { it.name == "x" && it.origin == SymbolOrigin.OUTER })
    }
}
