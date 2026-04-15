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

package net.sergeych.lyng.io.db

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.requireScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyngDbModuleTest {

    @Test
    fun testModuleRegistrationIsIdempotent() = runTest {
        val importManager = ImportManager()
        assertTrue(createDbModule(importManager))
        assertFalse(createDbModule(importManager))
    }

    @Test
    fun testOpenDatabaseDispatchesByNormalizedScheme() = runTest {
        val scope = Script.newScope()
        createDbModule(scope.importManager)
        val module = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")

        module.callFn(
            "registerDatabaseProvider",
            ObjString("TeSt"),
            ObjExternCallable.fromBridge {
                val url = requiredArg<ObjString>(0).value
                val params = requiredArg<Obj>(1)
                val size = (params.invokeInstanceMethod(requireScope(), "size") as ObjInt).value
                ObjString("$url|$size")
            }
        )

        val code = """
            import lyng.io.db
            openDatabase("TEST:demo", Map("a" => 1, "b" => 2))
        """.trimIndent()

        val result = Compiler.compile(Source("<db-test>", code), scope.importManager).execute(scope) as ObjString
        assertEquals("TEST:demo|2", result.value)
    }

    @Test
    fun testDuplicateSchemeRegistrationFailsCaseInsensitively() = runTest {
        val importManager = ImportManager()
        createDbModule(importManager)
        val module = importManager.createModuleScope(Pos.builtIn, "lyng.io.db")
        val scheme = "provider_test"

        module.callFn("registerDatabaseProvider", ObjString(scheme), trivialOpener())
        val error = try {
            module.callFn("registerDatabaseProvider", ObjString(scheme.uppercase()), trivialOpener())
            kotlin.test.fail("expected duplicate registration to fail")
        } catch (e: ExecutionError) {
            e
        }

        assertTrue(error.errorMessage.contains("already registered"), error.errorMessage)
    }

    @Test
    fun testMalformedUrlFailsWithIllegalArgument() = runTest {
        val scope = Script.newScope()
        createDbModule(scope.importManager)

        val code = """
            import lyng.io.db
            openDatabase("not-a-url", Map())
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(Source("<db-test>", code), scope.importManager).execute(scope)
        }

        assertEquals("IllegalArgumentException", error.errorObject.objClass.className)
        assertTrue(error.errorMessage.contains("Malformed database connection URL"), error.errorMessage)
    }

    @Test
    fun testUnknownSchemeFailsWithDatabaseException() = runTest {
        val scope = Script.newScope()
        createDbModule(scope.importManager)

        val code = """
            import lyng.io.db
            openDatabase("unknown:demo", Map())
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(Source("<db-test>", code), scope.importManager).execute(scope)
        }

        assertEquals("DatabaseException", error.errorObject.objClass.className)
        assertTrue(error.errorMessage.contains("No database provider registered"), error.errorMessage)
    }

    private suspend fun net.sergeych.lyng.ModuleScope.callFn(name: String, vararg args: Obj): Obj {
        val callee = get(name)?.value ?: error("Missing $name in module")
        return callee.invoke(this, ObjNull, *args)
    }

    private fun trivialOpener(): Obj = ObjExternCallable.fromBridge { ObjInt.One }
}
