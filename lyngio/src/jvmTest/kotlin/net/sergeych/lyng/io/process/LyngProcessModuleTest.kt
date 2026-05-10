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

package net.sergeych.lyng.io.process

import kotlinx.coroutines.runBlocking
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.Script
import net.sergeych.lyngio.process.security.PermitAllProcessAccessPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyngProcessModuleTest {

    @Test
    fun testLyngProcess() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)
        
        val code = """
            import lyng.io.process
            
            var p = Process.execute("echo", ["hello", "lyng"])
            var output = []
            for (line in p.stdout()) {
                output.add(line)
            }
            p.waitFor()
            output
        """.trimIndent()
        
        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("hello lyng"))
    }

    @Test
    fun testLyngShell() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)
        
        val code = """
            import lyng.io.process
            
            var p = Process.shell("echo 'shell lyng'")
            var output = ""
            for (line in p.stdout()) {
                output = output + line
            }
            p.waitFor()
            output
        """.trimIndent()
        
        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("shell lyng"))
    }

    @Test
    fun testPlatformDetails() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)
        
        val code = """
            import lyng.io.process
            Platform.details()
        """.trimIndent()
        
        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("name"), "Result should contain 'name', but was: ${result.inspect(scope)}")
    }

    @Test
    fun testShellSugarCapturesOutput() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)

        val code = """
            import lyng.io.process

            val r = sh("echo shell-sugar")
            assert(r.out.trim() == "shell-sugar")
            assert(r.code == 0)
            assert(r.ok == true)
            true
        """.trimIndent()

        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("true"))
    }

    @Test
    fun testShellSugarStreamsLines() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)

        val code = """
            import lyng.io.process

            val lines: List<String> = []
            for (line in sh("echo one && echo two").lines) {
                lines.add(line)
            }
            lines.joinToString(",")
        """.trimIndent()

        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("one,two"))
    }

    @Test
    fun testExecSugarCapturesOutput() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)

        val code = """
            import lyng.io.process

            exec("echo", ["exec-sugar"]).out.trim()
        """.trimIndent()

        val script = Compiler.compile(code)
        val result = script.execute(scope)
        assertTrue(result.inspect(scope).contains("exec-sugar"))
    }

    @Test
    fun testShellSugarCheckFailsOnNonZeroExit() = runBlocking {
        val scope = Script.newScope()
        createProcessModule(PermitAllProcessAccessPolicy, scope)

        val code = """
            import lyng.io.process

            sh("echo check-failed && exit 7").check()
        """.trimIndent()

        val script = Compiler.compile(code)
        assertFailsWith<ExecutionError> {
            script.execute(scope)
        }
        Unit
    }
}
