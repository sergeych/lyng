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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class CliAtExitJvmTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    private class TestExit(val code: Int) : RuntimeException()

    private data class CliResult(val out: String, val err: String, val exitCode: Int?)

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

    private fun runScript(scriptText: String): CliResult {
        val tmp: Path = Files.createTempFile("lyng_atexit_", ".lyng")
        try {
            Files.writeString(tmp, scriptText)
            return runCli(tmp.toString())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun atExitRunsInRequestedOrderAndIgnoresHandlerExceptions() {
        val result = runScript(
            """
            atExit {
                println("tail")
            }
            atExit(false) {
                println("head")
                throw Exception("ignored")
            }
            println("body")
            """.trimIndent()
        )

        assertNull(result.err.takeIf { it.isNotBlank() })
        assertNull(result.exitCode)
        val lines = result.out
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertEquals(listOf("body", "head", "tail"), lines)
    }

    @Test
    fun atExitRunsBeforeScriptExitTerminatesProcess() {
        val result = runScript(
            """
            atExit {
                println("cleanup")
            }
            exit(7)
            """.trimIndent()
        )

        assertEquals(7, result.exitCode)
        assertTrue(result.out.lineSequence().any { it.trim() == "cleanup" })
    }
}
