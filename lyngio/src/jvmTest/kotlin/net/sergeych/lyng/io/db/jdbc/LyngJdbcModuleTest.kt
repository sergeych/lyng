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

package net.sergeych.lyng.io.db.jdbc

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.requireScope
import kotlin.test.Test
import kotlin.test.assertEquals

class LyngJdbcModuleTest {

    @Test
    fun testTypedOpenH2ExecutesQueriesAndGeneratedKeys() = runTest {
        val scope = Script.newScope()
        createJdbcModule(scope.importManager)
        val jdbcModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.jdbc")
        val db = jdbcModule.callFn("openH2", ObjString("mem:typed_h2_${System.nanoTime()};DB_CLOSE_DELAY=-1"))

        val insertedId = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("create table person(id bigint auto_increment primary key, name varchar(120) not null)")
                )
                val result = tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("insert into person(name) values(?)"),
                    ObjString("Ada")
                )
                val rows = result.invokeInstanceMethod(requireScope(), "getGeneratedKeys")
                    .invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjInt.Zero)
            }
        ) as ObjInt

        assertEquals(1L, insertedId.value)
    }

    @Test
    fun testGenericOpenDatabaseUsesJdbcAndH2AliasProviders() = runTest {
        val scope = Script.newScope()
        createJdbcModule(scope.importManager)
        scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.jdbc")
        val dbModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")

        val genericJdbcDb = dbModule.callFn(
            "openDatabase",
            ObjString("jdbc:h2:mem:generic_jdbc_${System.nanoTime()};DB_CLOSE_DELAY=-1"),
            emptyMapObj()
        )
        val h2AliasDb = dbModule.callFn(
            "openDatabase",
            ObjString("h2:mem:generic_alias_${System.nanoTime()};DB_CLOSE_DELAY=-1"),
            emptyMapObj()
        )

        assertEquals(42L, scalarSelect(scope, genericJdbcDb, "select 42 as answer"))
        assertEquals(7L, scalarSelect(scope, h2AliasDb, "select 7 as answer"))
    }

    @Test
    fun testImportedJdbcOpenersPreserveDeclaredReturnTypesForInference() = runTest {
        val scope = Script.newScope()
        createJdbcModule(scope.importManager)

        val code = """
            import lyng.io.db
            import lyng.io.db.jdbc

            val h2db = openH2("mem:inference_demo;DB_CLOSE_DELAY=-1")
            h2db.transaction { 1 }

            val jdbcDb = openJdbc("jdbc:h2:mem:inference_demo_2;DB_CLOSE_DELAY=-1")
            jdbcDb.transaction { 2 }

            val genericDb = openDatabase("jdbc:h2:mem:inference_demo_3;DB_CLOSE_DELAY=-1", Map())
            genericDb.transaction { 3 }
        """.trimIndent()

        val result = Compiler.compile(Source("<jdbc-inference>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(3L, result.value)
    }

    private suspend fun scalarSelect(scope: net.sergeych.lyng.Scope, db: Obj, sql: String): Long {
        val result = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                val rows = tx.invokeInstanceMethod(requireScope(), "select", ObjString(sql))
                    .invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjString("answer"))
            }
        ) as ObjInt
        return result.value
    }

    private suspend fun net.sergeych.lyng.ModuleScope.callFn(name: String, vararg args: Obj): Obj {
        val callee = get(name)?.value ?: error("Missing $name in module")
        return callee.invoke(this, ObjNull, *args)
    }

    private fun emptyMapObj(): Obj = ObjMap()
}
