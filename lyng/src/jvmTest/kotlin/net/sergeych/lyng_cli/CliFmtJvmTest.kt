/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class CliFmtJvmTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    private class TestExit(val code: Int) : RuntimeException()

    @Before
    fun setUp() {
        // Make exit() throw in tests so we can assert the code
        jvmExitImpl = { code -> throw TestExit(code) }
    }

    @After
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        // restore default exit behavior for safety
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

    @Test
    fun fmtDoesNotPrintRootHelp() {
        val tmp: Path = Files.createTempFile("lyng_fmt_", ".lyng")
        try {
            Files.writeString(tmp, "println(1)\n")
            val r = runCli("fmt", tmp.toString())
            // Root help banner should not appear
            assertFalse(r.out.contains("The Lyng script language interpreter"))
            // Should output formatted content (stdout default)
            assertTrue("Expected some output", r.out.isNotBlank())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun fmtCheckAndInPlaceAreMutuallyExclusive() {
        val r = runCli("fmt", "--check", "--in-place", "nonexistent.lyng")
        // Should exit with code 1 and print an error
        assertTrue("Expected exit code 1", r.exitCode == 1)
        assertTrue(r.out.contains("cannot be used together"))
    }

    @Test
    fun fmtMultipleFilesPrintsHeaders() {
        val tmp1: Path = Files.createTempFile("lyng_fmt_", ".lyng")
        val tmp2: Path = Files.createTempFile("lyng_fmt_", ".lyng")
        try {
            Files.writeString(tmp1, "println(1)\n")
            Files.writeString(tmp2, "println(2)\n")
            val r = runCli("fmt", tmp1.toString(), tmp2.toString())
            assertTrue(r.out.contains("--- ${tmp1.toString()} ---"))
            assertTrue(r.out.contains("--- ${tmp2.toString()} ---"))
        } finally {
            Files.deleteIfExists(tmp1)
            Files.deleteIfExists(tmp2)
        }
    }

    @Test
    fun legacyPositionalScriptExecutes() {
        // Create a tiny script and ensure it runs when passed positionally
        val tmp: Path = Files.createTempFile("lyng_script_", ".lyng")
        try {
            Files.writeString(tmp, "println(\"OK\")\n")
            val r = runCli(tmp.toString())
            assertTrue(r.out.contains("OK"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun legacyDoubleDashStopsParsingAndExecutesScript() {
        val tmp: Path = Files.createTempFile("lyng_script_", ".lyng")
        try {
            Files.writeString(tmp, "println(\"DASH\")\n")
            val r = runCli("--", tmp.toString())
            assertTrue(r.out.contains("DASH"))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
