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
package net.sergeych.lyng_cli

import net.sergeych.jvmExitImpl
import net.sergeych.runMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

class CliLocalImportsJvmTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    private class TestExit(val code: Int) : RuntimeException()

    @Before
    fun setUp() {
        jvmExitImpl = { code -> throw TestExit(code) }
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        jvmExitImpl = { code -> kotlin.system.exitProcess(code) }
    }

    private data class CliResult(val out: String, val err: String, val exitCode: Int?)

    private fun runCli(vararg args: String): CliResult {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))

        var exitCode: Int? = null
        try {
            runMain(arrayOf(*args))
        } catch (e: TestExit) {
            exitCode = e.code
        } finally {
            System.out.flush()
            System.err.flush()
        }
        return CliResult(outBuf.toString("UTF-8"), errBuf.toString("UTF-8"), exitCode)
    }

    private fun writeTransitiveImportTree(root: java.nio.file.Path) {
        val packageDir = Files.createDirectories(root.resolve("package1"))
        val nestedDir = Files.createDirectories(packageDir.resolve("nested"))

        Files.writeString(
            packageDir.resolve("alpha.lyng"),
            """
            package package1.alpha

            import lyng.stdlib
            import lyng.io.net

            class Alpha {
                val headers = Map<String, String>()

                fun makeTask(port: Int, host: String): Deferred = launch {
                    host + ":" + port
                }

                fun netModule() = Net
            }

            fun alphaValue() = "alpha"
            """.trimIndent()
        )
        Files.writeString(
            packageDir.resolve("beta.lyng"),
            """
            package package1.beta

            import lyng.stdlib
            import package1.alpha

            fun betaValue() = alphaValue() + "|beta"
            """.trimIndent()
        )
        Files.writeString(
            nestedDir.resolve("gamma.lyng"),
            """
            package package1.nested.gamma

            import lyng.io.net
            import package1.alpha
            import package1.beta

            val String.gammaTag get() = this + "|gamma"

            fun gammaValue() = betaValue().gammaTag
            fun netModule() = Net
            """.trimIndent()
        )
        Files.writeString(
            packageDir.resolve("entry.lyng"),
            """
            package package1.entry

            import lyng.stdlib
            import lyng.io.net
            import package1.alpha
            import package1.beta
            import package1.nested.gamma

            fun report() = gammaValue() + "|entry"
            """.trimIndent()
        )
    }

    @Test
    fun cliDiscoversSiblingAndNestedLocalImportsFromEntryRoot() {
        val dir = Files.createTempDirectory("lyng_cli_local_imports_")
        try {
            val mathDir = Files.createDirectories(dir.resolve("math"))
            val utilDir = Files.createDirectories(dir.resolve("util"))
            val mainFile = dir.resolve("main.lyng")
            Files.writeString(
                mathDir.resolve("add.lyng"),
                """
                fun plus(a, b) = a + b
                """.trimIndent()
            )
            Files.writeString(
                utilDir.resolve("answer.lyng"),
                """
                package util.answer

                import math.add

                fun answer() = plus(40, 2)
                """.trimIndent()
            )
            Files.writeString(
                mainFile,
                """
                import util.answer

                println(answer())
                """.trimIndent()
            )

            val result = runCli(mainFile.toString())
            assertTrue(result.err, result.err.isBlank())
            assertTrue(result.out, result.out.contains("42"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cliRejectsPackageThatDoesNotMatchRelativePath() {
        val dir = Files.createTempDirectory("lyng_cli_local_imports_badpkg_")
        try {
            val utilDir = Files.createDirectories(dir.resolve("util"))
            val mainFile = dir.resolve("main.lyng")
            Files.writeString(
                utilDir.resolve("answer.lyng"),
                """
                package util.wrong

                fun answer() = 42
                """.trimIndent()
            )
            Files.writeString(
                mainFile,
                """
                import util.answer

                println(answer())
                """.trimIndent()
            )

            val result = runCli(mainFile.toString())
            assertTrue(result.out, result.out.contains("local module package mismatch"))
            assertTrue(result.out, result.out.contains("expected 'util.answer'"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cliHandlesOverlappingDirectoryImportsWithTransitiveStdlibAndNetSymbols() {
        val dir = Files.createTempDirectory("lyng_cli_local_imports_transitive_")
        try {
            val mainFile = dir.resolve("main.lyng")
            writeTransitiveImportTree(dir)
            Files.writeString(
                mainFile,
                """
                import package1.entry
                import package1.beta
                import package1.nested.gamma

                println(report())
                println(gammaValue())
                """.trimIndent()
            )

            val result = runCli(mainFile.toString())
            assertTrue(result.err, result.err.isBlank())
            assertTrue(
                result.out,
                result.out.contains("alpha|beta|gamma|entry")
            )
            assertTrue(
                result.out,
                result.out.contains("alpha|beta|gamma")
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
