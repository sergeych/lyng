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
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.requireScope
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals

class LyngJdbcPostgresContainerTest {

    @Test
    fun testOpenPostgresAgainstContainer() = runTest {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()

            val scope = Script.newScope()
            createJdbcModule(scope.importManager)
            val jdbcModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.jdbc")
            val db = jdbcModule.callFn(
                "openPostgres",
                ObjString(postgres.jdbcUrl),
                ObjString(postgres.username),
                ObjString(postgres.password)
            )

            val count = rowCount(scope, db, "people", "Ada", "Linus")
            assertEquals(2L, count)
        }
    }

    @Test
    fun testGenericPostgresAliasAgainstContainer() = runTest {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()

            val scope = Script.newScope()
            createJdbcModule(scope.importManager)
            scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.jdbc")
            val dbModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")
            val aliasUrl = postgres.jdbcUrl.removePrefix("jdbc:postgresql:")
            val db = dbModule.callFn(
                "openDatabase",
                ObjString("postgres:$aliasUrl"),
                mapOfStrings(
                    "user" to postgres.username,
                    "password" to postgres.password
                )
            )

            val count = rowCount(scope, db, "pets", "Milo", "Otis", "Pixel")
            assertEquals(3L, count)
        }
    }

    private suspend fun rowCount(scope: net.sergeych.lyng.Scope, db: Obj, table: String, vararg names: String): Long {
        val result = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("create table $table(id bigserial primary key, name text not null)")
                )
                for (name in names) {
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into $table(name) values(?)"),
                        ObjString(name)
                    )
                }
                val rows = tx.invokeInstanceMethod(
                    requireScope(),
                    "select",
                    ObjString("select count(*) as count from $table")
                ).invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjString("count"))
            }
        ) as ObjInt
        return result.value
    }

    private suspend fun mapOfStrings(vararg entries: Pair<String, String>): Obj {
        val map = ObjMap()
        for ((key, value) in entries) {
            map.map[ObjString(key)] = ObjString(value)
        }
        return map
    }

    private suspend fun net.sergeych.lyng.ModuleScope.callFn(name: String, vararg args: Obj): Obj {
        val callee = get(name)?.value ?: error("Missing $name in module")
        return callee.invoke(this, ObjNull, *args)
    }
}
