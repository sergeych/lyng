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
import net.sergeych.lyng.toSource
import net.sergeych.lyng.bytecode.CmdDisassembler
import net.sergeych.lyng.bytecode.CmdFunction
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BytecodeRecentOpsJvmTest {

    @Test
    fun moduleDeclsAvoidCallableCallSlots() = runTest {
        val script = """
            class A {}
            fun f() { 1 }
            enum E { one }
        """.trimIndent()
        val compiled = Compiler.compile(script.toSource(), Script.defaultImportManager)
        val field = Script::class.java.getDeclaredField("moduleBytecode")
        field.isAccessible = true
        val moduleFn = field.get(compiled) as? CmdFunction
        assertNotNull(moduleFn, "module bytecode missing")
        val disasm = CmdDisassembler.disassemble(moduleFn)
        assertTrue(!disasm.contains("CALL_SLOT"), disasm)
        assertTrue(!disasm.contains("Callable@"), disasm)
        assertTrue(disasm.contains("DECL_CLASS"), disasm)
        assertTrue(disasm.contains("DECL_FUNCTION"), disasm)
        assertTrue(disasm.contains("DECL_ENUM"), disasm)
    }
}
