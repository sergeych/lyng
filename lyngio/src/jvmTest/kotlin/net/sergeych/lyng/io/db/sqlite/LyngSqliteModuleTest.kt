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
import kotlinx.datetime.TimeZone
import net.sergeych.lyng.Compiler
import net.sergeych.lyng.ExecutionError
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Pos
import net.sergeych.lyng.Scope
import net.sergeych.lyng.Script
import net.sergeych.lyng.Source
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.obj.requiredArg
import net.sergeych.lyng.requireScope
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

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
    fun testImportedDatabaseOpenersPreserveDeclaredReturnTypesForInference() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db
            import lyng.io.db.sqlite

            val typedDb = openSqlite(":memory:")
            typedDb.transaction { 1 }

            val genericDb = openDatabase("sqlite::memory:", Map())
            genericDb.transaction { 2 }
        """.trimIndent()

        Compiler.compile(Source("<sqlite-inference>", code), scope.importManager).execute(scope)
    }

    @Test
    fun testTransactionLambdaParameterTypeIsInferredFromDatabaseSignature() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db
            import lyng.io.db.sqlite
            import lyng.time

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table item(id integer primary key autoincrement, name text not null, due_date date not null)")
                tx.execute("insert into item(name, due_date) values(?, ?)", "outer", Date(2026, 4, 16))
                tx.transaction { inner ->
                    inner.execute("insert into item(name, due_date) values(?, ?)", "inner", Date(2026, 4, 17))
                    1
                }
                2
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-lambda-inference>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(2L, result.value)
    }

    @Test
    fun testDecodeAsProjectsJsonColumnIntoObjectField() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite

            class Point(x: Int, y: Int)
            class Row(id: Int, payload: Point)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(id integer not null, payload json not null)")
                tx.execute("insert into data(id, payload) values(?, ?)", 7, "{\"x\":4,\"y\":5}")
                val row = tx.select("select id, payload from data").decodeAs<Row>().first
                assertEquals(7, row.id)
                assertEquals(4, row.payload.x)
                assertEquals(5, row.payload.y)
                row.payload.y
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-decode-json-field>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(5L, result.value)
    }

    @Test
    fun testDecodeAsSupportsSingleJsonColumnProjection() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite

            class Point(x: Int, y: Int)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(payload json not null)")
                tx.execute("insert into data(payload) values(?)", "{\"x\":9,\"y\":11}")
                val point = tx.select("select payload from data").decodeAs<Point>().first
                assertEquals(9, point.x)
                assertEquals(11, point.y)
                point.x + point.y
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-decode-json-single>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(20L, result.value)
    }

    @Test
    fun testDecodeAsDoesNotAutoDecodePlainTextAsJson() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite

            class Point(x: Int, y: Int)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(payload text not null)")
                tx.execute("insert into data(payload) values(?)", "{\"x\":1,\"y\":2}")
                tx.select("select payload from data").decodeAs<Point>().first
            }
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(Source("<sqlite-decode-json-text-guard>", code), scope.importManager).execute(scope)
        }
        assertEquals("SqlUsageException", error.errorObject.objClass.className)
    }

    @Test
    fun testDecodeAsSupportsSingleLynonBinaryProjection() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite
            import lyng.serialization

            class Point(x: Int, y: Int)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(payload blob not null)")
                tx.execute("insert into data(payload) values(?)", Lynon.encode(Point(6, 8)).toBuffer())
                val point = tx.select("select payload from data").decodeAs<Point>().first
                assertEquals(6, point.x)
                assertEquals(8, point.y)
                point.x + point.y
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-decode-lynon-single>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(14L, result.value)
    }

    @Test
    fun testDecodeAsSupportsDbDecodeWithOnConstructorParamsAndFields() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db
            import lyng.io.db.sqlite

            object TrimmedStringAdapter: DbFieldAdapter {
                override fun decode(rawValue, column, row, targetType) =
                    when(rawValue) {
                        null -> null
                        else -> rawValue.toString().trim()
                    }
            }

            class User(
                id: Int,
                @DbDecodeWith(TrimmedStringAdapter) name: String
            ) {
                @DbDecodeWith(TrimmedStringAdapter)
                var note: String = ""
            }

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(id integer not null, name text not null, note text not null)")
                tx.execute("insert into data(id, name, note) values(?, ?, ?)", 10, "  Alice  ", "  hello  ")
                val user = tx.select("select id, name, note from data").decodeAs<User>().first
                assertEquals(10, user.id)
                assertEquals("Alice", user.name)
                assertEquals("hello", user.note)
                user.note.size
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-decode-dbdecodewith>", code), scope.importManager).execute(scope) as ObjInt
        assertEquals(5L, result.value)
    }

    @Test
    fun testDecodeAsFailsWhenDbDecodeWithReturnsWrongType() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db
            import lyng.io.db.sqlite

            object BadAdapter: DbFieldAdapter {
                override fun decode(rawValue, column, row, targetType) = 42
            }

            class User(@DbDecodeWith(BadAdapter) name: String)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(name text not null)")
                tx.execute("insert into data(name) values(?)", "Alice")
                tx.select("select name from data").decodeAs<User>().first
            }
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(Source("<sqlite-decode-dbdecodewith-bad-type>", code), scope.importManager).execute(scope)
        }
        assertEquals("SqlUsageException", error.errorObject.objClass.className)
    }

    @Test
    fun testDecodeAsKeepsRawBufferForBufferTarget() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite
            import lyng.buffer
            import lyng.serialization

            class Point(x: Int, y: Int)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(payload blob not null)")
                val encoded = Lynon.encode(Point(1, 2)).toBuffer()
                tx.execute("insert into data(payload) values(?)", encoded)
                val payload = tx.select("select payload from data").decodeAs<Buffer>().first
                assertEquals(encoded.size, payload.size)
                payload.size
            }
        """.trimIndent()

        val result = Compiler.compile(Source("<sqlite-decode-buffer-raw>", code), scope.importManager).execute(scope) as ObjInt
        assertTrue(result.value > 0)
    }

    @Test
    fun testDecodeAsFailsForNonLynonBinaryTypedProjection() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)

        val code = """
            import lyng.io.db.sqlite
            import lyng.buffer

            class Point(x: Int, y: Int)

            val db = openSqlite(":memory:")
            db.transaction { tx ->
                tx.execute("create table data(payload blob not null)")
                tx.execute("insert into data(payload) values(?)", "hello".encodeUtf8())
                tx.select("select payload from data").decodeAs<Point>().first
            }
        """.trimIndent()

        val error = assertFailsWith<ExecutionError> {
            Compiler.compile(Source("<sqlite-decode-lynon-binary-guard>", code), scope.importManager).execute(scope)
        }
        assertEquals("SqlUsageException", error.errorObject.objClass.className)
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
    fun testRollbackExceptionRollsBackAndPropagates() = runTest {
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
                        ObjString("create table items(id integer primary key autoincrement, name text not null)")
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
                            ObjString("insert into items(name) values(?)"),
                            ObjString("rolled-back")
                        )
                        rollbackException(requireScope(), "stop here").raiseAsExecutionError(requireScope())
                    }
                )
            }

            assertEquals("RollbackException", error.errorObject.objClass.className)

            val count = db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    val resultSet = tx.invokeInstanceMethod(requireScope(), "select", ObjString("select count(*) as count from items"))
                    rowsOf(requireScope(), resultSet)[0].getAt(requireScope(), ObjString("count"))
                }
            ) as ObjInt

            assertEquals(0L, count.value)
        }
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
    fun testMaterializedRowSurvivesAfterTransactionEnds() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)
        var leakedRow: Obj = ObjNull

        db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                leakedRow = rowsOf(requireScope(), tx.invokeInstanceMethod(requireScope(), "select", ObjString("select 42 as answer")))[0]
                ObjNull
            }
        )

        val answer = leakedRow.getAt(scope, ObjString("answer")) as ObjInt
        assertEquals(42L, answer.value)
    }

    @Test
    fun testMaterializedRowsListCanBeReturnedFromTransaction() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val rows = db.invokeInstanceMethod(
            scope,
            "transaction",
            ObjExternCallable.fromBridge {
                val tx = requiredArg<Obj>(0)
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("create table items(id integer primary key autoincrement, name text not null)")
                )
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("alpha"))
                tx.invokeInstanceMethod(requireScope(), "execute", ObjString("insert into items(name) values(?)"), ObjString("beta"))
                tx.invokeInstanceMethod(requireScope(), "select", ObjString("select name from items order by id"))
                    .invokeInstanceMethod(requireScope(), "toList")
            }
        )

        assertEquals("alpha", stringValue(scope, rows.getAt(scope, ObjInt.Zero).getAt(scope, ObjString("name"))))
        assertEquals("beta", stringValue(scope, rows.getAt(scope, ObjInt.of(1)).getAt(scope, ObjString("name"))))
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
    fun testDateAndBooleanConversionRules() = runTest {
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
                        "create table sample(" +
                            "flag BOOL not null, " +
                            "day DATE not null, " +
                            "clock TIME not null)"
                    )
                )
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("insert into sample(flag, day, clock) values(?, ?, ?)"),
                    ObjBool(true),
                    dateOf(requireScope(), "2026-04-15"),
                    ObjString("12:34:56")
                )
                tx.invokeInstanceMethod(
                    requireScope(),
                    "execute",
                    ObjString("insert into sample(flag, day, clock) values(?, ?, ?)"),
                    ObjString("t"),
                    ObjString("2026-04-16"),
                    ObjString("23:59:59")
                )
                val resultSet = tx.invokeInstanceMethod(
                    requireScope(),
                    "select",
                    ObjString("select flag, day, clock from sample order by day")
                )
                val rows = rowsOf(requireScope(), resultSet)
                ObjString(
                    listOf(
                        rows[0].getAt(requireScope(), ObjString("flag")).objClass.className,
                        rows[0].getAt(requireScope(), ObjString("day")).objClass.className,
                        stringValue(requireScope(), rows[0].getAt(requireScope(), ObjString("clock"))),
                        rows[1].getAt(requireScope(), ObjString("flag")).objClass.className,
                    ).joinToString("|")
                )
            }
        ) as ObjString

        assertEquals("Bool|Date|12:34:56|Bool", summary.value)
    }

    @Test
    fun testUnsupportedParameterTypeFailsWithSqlUsageException() = runTest {
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
                        ObjString("create table sample(value text not null)")
                    )
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into sample(value) values(?)"),
                        emptyMapObj()
                    )
                }
            )
        }

        assertEquals("SqlUsageException", error.errorObject.objClass.className)
        assertTrue(error.errorMessage.contains("Unsupported SQLite parameter type"), error.errorMessage)
    }

    @Test
    fun testTimestampAndDatetimeRejectTimezoneBearingText() = runTest {
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
                        ObjString("create table sample(ts TIMESTAMP not null, dt DATETIME not null)")
                    )
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into sample(ts, dt) values(?, ?)"),
                        ObjString("2024-05-06T07:08:09Z"),
                        ObjString("2024-05-06T10:11:12+03:00")
                    )
                }
            )

            val timestampError = assertFailsWith<ExecutionError> {
                db.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge {
                        val tx = requiredArg<Obj>(0)
                        tx.invokeInstanceMethod(requireScope(), "select", ObjString("select ts from sample"))
                    }
                )
            }
            assertEquals("SqlExecutionException", timestampError.errorObject.objClass.className)
            assertTrue(timestampError.errorMessage.contains("must not contain a timezone offset"), timestampError.errorMessage)

            val datetimeError = assertFailsWith<ExecutionError> {
                db.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge {
                        val tx = requiredArg<Obj>(0)
                        tx.invokeInstanceMethod(requireScope(), "select", ObjString("select dt from sample"))
                    }
                )
            }
            assertEquals("SqlExecutionException", datetimeError.errorObject.objClass.className)
            assertTrue(datetimeError.errorMessage.contains("must not contain a timezone offset"), datetimeError.errorMessage)
        }
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

    @Test
    fun testMissingFileWithCreateIfMissingFalseFailsWithDatabaseException() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val missingDir = Files.createTempDirectory("lyng-sqlite-missing-dir-")
        missingDir.deleteIfExists()
        val missingFile = missingDir.resolve("missing.db")

        try {
            val db = sqliteModule.callFn(
                "openSqlite",
                ObjString(missingFile.toString()),
                net.sergeych.lyng.obj.ObjFalse,
                net.sergeych.lyng.obj.ObjFalse
            )

            val error = assertFailsWith<ExecutionError> {
                db.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge { ObjNull }
                )
            }

            assertEquals("DatabaseException", error.errorObject.objClass.className)
        } finally {
            missingFile.deleteIfExists()
            missingDir.deleteIfExists()
        }
    }

    @Test
    fun testGenericOpenDatabaseReadOnlyOptionMatchesTypedHelper() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val dbModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db")
        val tempFile = Files.createTempFile("lyng-sqlite-generic-", ".db")

        try {
            val writableDb = dbModule.callFn("openDatabase", ObjString("sqlite:${tempFile}"), sqliteOptions(scope))
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

            val readOnlyDb = dbModule.callFn(
                "openDatabase",
                ObjString("sqlite:${tempFile}"),
                sqliteOptions(
                    scope,
                    "readOnly" to net.sergeych.lyng.obj.ObjTrue,
                    "createIfMissing" to net.sergeych.lyng.obj.ObjFalse
                )
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

    @Test
    fun testForeignKeysOptionControlsConstraintEnforcement() = runTest {
        val scope = Script.newScope()
        createSqliteModule(scope.importManager)
        val sqliteModule = scope.importManager.createModuleScope(Pos.builtIn, "lyng.io.db.sqlite")
        val tempFile = Files.createTempFile("lyng-sqlite-fk-", ".db")

        try {
            val dbNoFk = sqliteModule.callFn(
                "openSqlite",
                ObjString(tempFile.toString()),
                net.sergeych.lyng.obj.ObjFalse,
                net.sergeych.lyng.obj.ObjTrue,
                net.sergeych.lyng.obj.ObjFalse
            )
            dbNoFk.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(requireScope(), "execute", ObjString("create table parent(id integer primary key)"))
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("create table child(parent_id integer not null references parent(id))")
                    )
                    tx.invokeInstanceMethod(
                        requireScope(),
                        "execute",
                        ObjString("insert into child(parent_id) values(?)"),
                        ObjInt.One
                    )
                }
            )

            val dbWithFk = sqliteModule.callFn("openSqlite", ObjString(tempFile.toString()))
            val error = assertFailsWith<ExecutionError> {
                dbWithFk.invokeInstanceMethod(
                    scope,
                    "transaction",
                    ObjExternCallable.fromBridge {
                        val tx = requiredArg<Obj>(0)
                        tx.invokeInstanceMethod(
                            requireScope(),
                            "execute",
                            ObjString("insert into child(parent_id) values(?)"),
                            ObjInt.of(2)
                        )
                    }
                )
            }

            assertEquals("SqlConstraintException", error.errorObject.objClass.className)
        } finally {
            tempFile.deleteIfExists()
        }
    }

    @Test
    fun testCommitFailureBecomesPrimaryAfterNormalCompletion() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val error = assertFailsWith<ExecutionError> {
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(requireScope(), "execute", ObjString("rollback"))
                }
            )
        }

        assertEquals("SqlExecutionException", error.errorObject.objClass.className)
    }

    @Test
    fun testUserExceptionStaysPrimaryWhenRollbackFails() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val error = assertFailsWith<IllegalStateException> {
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(requireScope(), "execute", ObjString("rollback"))
                    throw IllegalStateException("boom")
                }
            )
        }

        assertEquals("boom", error.message)
    }

    @Test
    fun testRollbackFailureBecomesPrimaryAfterRollbackException() = runTest {
        val scope = Script.newScope()
        val db = openMemoryDb(scope)

        val error = assertFailsWith<ExecutionError> {
            db.invokeInstanceMethod(
                scope,
                "transaction",
                ObjExternCallable.fromBridge {
                    val tx = requiredArg<Obj>(0)
                    tx.invokeInstanceMethod(requireScope(), "execute", ObjString("rollback"))
                    rollbackException(requireScope(), "rollback requested").raiseAsExecutionError(requireScope())
                }
            )
        }

        assertEquals("SqlExecutionException", error.errorObject.objClass.className)
    }

    private suspend fun ModuleScope.callFn(name: String, vararg args: Obj): Obj {
        val callee = get(name)?.value ?: error("Missing $name in module")
        return callee.invoke(this, ObjNull, *args)
    }

    private suspend fun sqliteOptions(scope: Scope, vararg entries: Pair<String, Obj>): ObjMap {
        val result = ObjMap()
        for ((key, value) in entries) {
            result.putAt(scope, ObjString(key), value)
        }
        return result
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

    private suspend fun rollbackException(scope: Scope, message: String): net.sergeych.lyng.obj.ObjException {
        val dbModule = scope.currentImportProvider.createModuleScope(scope.pos, "lyng.io.db")
        val rollbackClass = dbModule.requireClass("RollbackException")
        return rollbackClass.invoke(scope, ObjNull, ObjString(message)) as net.sergeych.lyng.obj.ObjException
    }

    private suspend fun dateOf(scope: Scope, value: String): Obj {
        val timeModule = scope.currentImportProvider.createModuleScope(scope.pos, "lyng.time")
        val dateClass = timeModule.requireClass("Date")
        return dateClass.invoke(scope, ObjNull, ObjString(value))
    }

    private fun emptyMapObj(): Obj = ObjMap()
}
