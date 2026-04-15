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

package net.sergeych.lyng.io.db.sqlite

import kotlinx.coroutines.test.runTest
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjExternCallable
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjMap
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.requireScope
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyngSqliteModuleTest {

    @Test
    fun testTypedOpenSqliteExecutesQueriesAndGeneratedKeys() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val db = sqliteModule.callFn("openSqlite", ObjString(":memory:"))

        val insertedId = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("create table person(id integer primary key autoincrement, name text not null)"))
                val result = tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("insert into person(name) values(?)"),
                    ObjString("John Doe")
                )
                val generatedKeys = result.invokeInstanceMethod(requireScope(), "getGeneratedKeys")
                val rows = generatedKeys.invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjInt.Zero)
            }
        ) as ObjInt

        assertEquals(1L, insertedId.value)
    }

    @Test
    fun testGenericOpenDatabaseUsesRegisteredSqliteProvider() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val dbModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")
        val db = dbModule.callFn("openDatabase", ObjString("sqlite::memory:"), emptyMapObj())

        val count = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("create table items(id integer primary key autoincrement, name text not null)"))
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("alpha"))
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("beta"))
                val resultSet = tx.invokeInstanceMethod(requireScope(), "select", ObjString("select count(*) as count from items"))
                val rows = resultSet.invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjString("count"))
            }
        ) as ObjInt

        assertEquals(2L, count.value)
    }

    @Test
    fun testNestedTransactionRollbackUsesSavepoint() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val db = sqliteModule.callFn("openSqlite", ObjString(":memory:"))

        val count = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("create table items(id integer primary key autoincrement, name text not null)"))
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("outer"))
                try {
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "transaction",
                        ObjExternCallable.fromBridge {
                            val inner = requiredArg<Obj>(0)
                            inner.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("inner"))
                            throw IllegalStateException("rollback nested")
                        }
                    )
                } catch (_: IllegalStateException) {
                }
                val resultSet = tx.invokeInstanceMethod(requireScope(), "select", ObjString("select count(*) as count from items"))
                val rows = resultSet.invokeInstanceMethod(requireScope(), "toList")
                rows.getAt(requireScope(), ObjInt.Zero).getAt(requireScope(), ObjString("count"))
            }
        ) as ObjInt

        assertEquals(1L, count.value)
    }

    @Test
    fun testResultSetFailsAfterTransactionEnds() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val db = sqliteModule.callFn("openSqlite", ObjString(":memory:"))
        var leakedResultSet: Obj = ObjNull

        db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("create table items(id integer primary key autoincrement, name text not null)"))
                leakedResultSet = tx.invokeInstanceMethod(requireScope(), "select", ObjString("select 42 as answer"))
                ObjNull
            }
        )

        val error = assertFailsWith<ExecutionError> {
            leakedResultSet.invokeInstanceMethod(scope, "size")
        }

        assertEquals("SqlUsageException", error.errorObject.objClass.className)
        assertTrue(error.errorMessage.contains("transaction is active"), error.errorMessage)
    }

    @Test
    fun testInvalidSqliteUrlFailsWithIllegalArgument() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val dbModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")

        val error = assertFailsWith<ExecutionError> {
            dbModule.callFn("openDatabase", ObjString("sqlite://bad"), emptyMapObj())
        }

        assertEquals("IllegalArgumentException", error.errorObject.objClass.className)
    }

    @Test
    fun testConstraintViolationIsMappedToSqlConstraintException() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val error = assertFailsWith<ExecutionError> {
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("create table person(id integer primary key autoincrement, email text unique not null)")
                    )
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into person(email) values(?)"),
                        ObjString("a@example.com")
                    )
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into person(email) values(?)"),
                        ObjString("a@example.com")
                    )
                }
            )
        }

        assertEquals("SqlConstraintException", error.errorObject.objClass.className)
    }

    @Test
    fun testAmbiguousColumnNameAccessFailsWithSqlUsageException() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val error = assertFailsWith<ExecutionError> {
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    val resultSet = tx.invokeInstanceMethod(
                        requireScope(),
                        "select",
                        ObjString("select 1 as value, 2 as value")
                    )
                    val row = rowsOf(requireScope(), resultSet)[0]
                    row.getAt(requireScope(), ObjString("value"))
                }
            )
        }

        assertEquals("SqlUsageException", error.errorObject.objClass.className)
        assertTrue(error.errorMessage.contains("Ambiguous SQL result column"), error.errorMessage)
    }

    @Test
    fun testExecuteRejectsReturningButSelectSupportsIt() = runTest {
        val scope = Script.newScope()
        withTempDb(scope) { db ->
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("create table item(id integer primary key autoincrement, name text not null)")
                    )
                }
            )

            val error = assertFailsWith<ExecutionError> {
                db.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge {
                        val tx = requiredArg<Obj>(0)
                        tx.invokeInstanceMethod(
                            requireScope(),
                            "execute",
                            ObjString("insert into item(name) values(?) returning id"),
                            ObjString("bad")
                        )
                    }
                )
            }

            assertEquals("SqlUsageException", error.errorObject.objClass.className)

            val insertedId = db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    val resultSet = tx.invokeInstanceMethod(
                        requireScope(),
                        "select",
                        ObjString("insert into item(name) values(?) returning id"),
                        ObjString("good")
                    )
                    val row = rowsOf(requireScope(), resultSet)[0]
                    row.getAt(requireScope(), ObjString("id"))
                }
            ) as ObjInt

            assertEquals(1L, insertedId.value)
        }
    }

    @Test
    fun testColumnMetadataAndTypedValueConversion() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val summary = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString(
                        "create table events(" +
                            "amount NUMERIC not null, " +
                            "happened TIMESTAMPTZ not null, " +
                            "scheduled TIMESTAMP not null, " +
                            "note TEXT not null, " +
                            "payload BLOB not null)"
                    )
                )
                val decimal = decimalOf(requireScope(), "12.50")
                val happened = ObjInstant(Instant.parse("2024-05-06T07:08:09Z"))
                val scheduled = ObjDateTime(Instant.parse("2024-05-06T10:11:12Z"), TimeZone.UTC)
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("insert into events(amount, happened, scheduled, note, payload) values(?, ?, ?, ?, ?)"),
                    decimal,
                    happened,
                    scheduled,
                    ObjString("hello"),
                    ObjBuffer(byteArrayOf(1, 2, 3).toUByteArray())
                )
                val resultSet = tx.invokeInstanceMethod(
                    requireScope(),
                    "select",
                    ObjString("select amount, happened, scheduled, note, payload from events")
                )
                val columns = field(requireScope(), resultSet, "columns")
                val firstColumn = columns.getAt(requireScope(), ObjInt.Zero)
                val row = rowsOf(requireScope(), resultSet)[0]
                ObjString(
                    listOf(
                        stringValue(requireScope(), field(requireScope(), firstColumn, "name")),
                        enumName(requireScope(), field(requireScope(), firstColumn, "sqlType")),
                        stringValue(requireScope(), field(requireScope(), firstColumn, "nativeType")),
                        row.getAt(requireScope(), ObjString("amount")).objClass.className,
                        row.getAt(requireScope(), ObjString("happened")).objClass.className,
                        row.getAt(requireScope(), ObjString("scheduled")).objClass.className,
                        stringValue(requireScope(), row.getAt(requireScope(), ObjString("note"))),
                        row.getAt(requireScope(), ObjString("payload")).objClass.className,
                    ).joinToString("|")
                )
            }
        ) as ObjString

        assertEquals(
            "amount|Decimal|NUMERIC|Decimal|Instant|DateTime|hello|Buffer",
            summary.value
        )
    }

    @Test
    fun testReadOnlyOpenPreventsWrites() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val tempFile = Files.createTempFile("lyng-sqlite-", ".db")
        try {
            val writableDb = sqliteModule.callFn("openSqlite", ObjString(tempFile.toString()))
            writableDb.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("create table item(id integer primary key autoincrement, name text not null)")
                    )
                }
            )

            val readOnlyDb = sqliteModule.callFn(
                "openSqlite",
                ObjString(tempFile.toString()),
                net.sergeych.lyng.obj.ObjTrue,
                net.sergeych.lyng.obj.ObjFalse
            )

            val error = assertFailsWith<ExecutionError> {
                readOnlyDb.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge {
                        val tx = requiredArg<Obj>(0)
                        tx.invokeInstanceMethod(
                            requireScope(),
                            "execute",
                            ObjString("insert into item(name) values(?)"),
                            ObjString("blocked")
                        )
                    }
                )
            }

            assertEquals("SqlExecutionException", error.errorObject.objClass.className)
        } finally {
            tempFile.deleteIfExists()
        }
    }

    private suspend fun ModuleScope.callFn(name: String, vararg args: Obj): Obj {
        val callee = get(name)?.value ?: error("Missing $name in module")
        return callee.invoke(this, ObjNull, *args)
    }

    private suspend fun openMemoryDb(scope: Scope): Obj {
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        return sqliteModule.callFn("openSqlite", ObjString(":memory:"))
    }

    private suspend fun withTempDb(scope: Scope, block: suspend (Obj) -> Unit) {
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val tempFile = Files.createTempFile("lyng-sqlite-", ".db")
        try {
            val db = sqliteModule.callFn("openSqlite", ObjString(tempFile.toString()))
            block(db)
        } finally {
            tempFile.deleteIfExists()
        }
    }

    private suspend fun field(scope: Scope, obj: Obj, name: String): Obj =
        obj.readField(scope, name).value

    private suspend fun rowsOf(scope: Scope, resultSet: Obj): List<Obj> {
        val rows = resultSet.invokeInstanceMethod(scope, "toList")
        val size = (field(scope, rows, "size") as ObjInt).value.toInt()
        return (0 until size).map { index -> rows.getAt(scope, ObjInt.of(index.toLong())) }
    }

    private suspend fun stringValue(scope: Scope, obj: Obj): String =
        (obj as? ObjString ?: obj.toString(scope)) .value

    private suspend fun enumName(scope: Scope, obj: Obj): String =
        stringValue(scope, field(scope, obj, "name"))

    private suspend fun decimalOf(scope: Scope, value: String): Obj {
        val decimalModule = scope.currentImportProvider.createModuleScope(scope.pos, "lyng.decimal")
        val decimalClass = decimalModule.requireClass("Decimal")
        return decimalClass.invokeInstanceMethod(scope, "fromString", ObjString(value))
    }

    private fun emptyMapObj(): Obj = ObjMap()
}
