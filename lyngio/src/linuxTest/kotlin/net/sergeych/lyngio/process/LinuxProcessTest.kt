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

package net.sergeych.lyngio.process

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Script
import net.sergeych.lyng.io.process.createProcessModule
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxProcessTest {

    @Test
    fun testExecuteEcho() = runBlocking {
        val process = PosixProcessRunner.execute("echo", listOf("hello", "native"))
        val stdout = process.stdout.toList()
        val exitCode = process.waitFor()
        
        assertEquals(0, exitCode)
        assertEquals(listOf("hello native"), stdout)
    }

    @Test
    fun testShellCommand() = runBlocking {
        val process = PosixProcessRunner.shell("echo 'shell native' && printf 'line2'")
        val stdout = process.stdout.toList()
        val exitCode = process.waitFor()
        
        assertEquals(0, exitCode)
        assertEquals(listOf("shell native", "line2"), stdout)
    }

    @Test
    fun testStderrCapture() = runBlocking {
        val process = PosixProcessRunner.shell("echo 'to stdout'; echo 'to stderr' >&2")
        val stdout = process.stdout.toList()
        val stderr = process.stderr.toList()
        process.waitFor()
        
        assertEquals(listOf("to stdout"), stdout)
        assertEquals(listOf("to stderr"), stderr)
    }

    @Test
    fun testPlatformDetails() {
        val details = getPlatformDetails()
        assertEquals("LINUX", details.name)
        assertTrue(details.kernelVersion != null)
        assertTrue(details.kernelVersion!!.isNotEmpty())
        println("Linux Native Details: $details")
    }

    @Test
    fun testLyngModuleNative() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)

        val code = """
            import lyng.io.process
            
            var p = Process.execute("echo", ["hello", "lyng", "native"])
            var output = []
            for (line in p.stdout()) {
                output.add(line)
            }
            p.waitFor()
            println(output)
            assertEquals("hello lyng native", output.joinToString(" "))
            output
        """.trimIndent()

        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("hello lyng native"))
    }
}
