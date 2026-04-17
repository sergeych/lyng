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

import net.sergeych.lyng.Arguments
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjImmutableList
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.requireScope
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.associateWith
import kotlin.collections.drop
import kotlin.collections.first
import kotlin.collections.forEachIndexed
import kotlin.collections.getOrNull
import kotlin.collections.getOrPut
import kotlin.collections.indices
import kotlin.collections.linkedMapOf
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.text.lowercase

internal data class SqlColumnMeta(
    val name: String,
    val sqlType: ObjEnumEntry,
    val nullable: Boolean,
    val nativeType: String,
)

internal data class SqlResultSetData(
    val columns: List<SqlColumnMeta>,
    val rows: List<List<Obj>>,
)

internal data class SqlExecutionResultData(
    val affectedRowsCount: Int,
    val generatedKeys: SqlResultSetData,
)

internal interface SqlDatabaseBackend {
    suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqlTransactionBackend) -> T): T
    fun close() {}
}

internal interface SqlTransactionBackend {
    suspend fun select(scope: ScopeFacade, clause: String, params: List<Obj>): SqlResultSetData
    suspend fun execute(scope: ScopeFacade, clause: String, params: List<Obj>): SqlExecutionResultData
    suspend fun <T> transaction(scope: ScopeFacade, block: suspend (SqlTransactionBackend) -> T): T
}

internal class SqlCoreModule private constructor(
    val module: ModuleScope,
    val databaseClass: ObjClass,
    val transactionClass: ObjClass,
    val resultSetClass: ObjClass,
    val rowClass: ObjClass,
    val columnClass: ObjClass,
    val executionResultClass: ObjClass,
    val databaseException: ObjException.Companion.ExceptionClass,
    val sqlExecutionException: ObjException.Companion.ExceptionClass,
    val sqlConstraintException: ObjException.Companion.ExceptionClass,
    val sqlUsageException: ObjException.Companion.ExceptionClass,
    val rollbackException: ObjException.Companion.ExceptionClass,
    val sqlTypes: SqlTypeEntries,
) {
    companion object {
        fun resolve(module: ModuleScope): SqlCoreModule = SqlCoreModule(
            module = module,
            databaseClass = module.requireClass("Database"),
            transactionClass = module.requireClass("SqlTransaction"),
            resultSetClass = module.requireClass("ResultSet"),
            rowClass = module.requireClass("SqlRow"),
            columnClass = module.requireClass("SqlColumn"),
            executionResultClass = module.requireClass("ExecutionResult"),
            databaseException = module.requireClass("DatabaseException") as ObjException.Companion.ExceptionClass,
            sqlExecutionException = module.requireClass("SqlExecutionException") as ObjException.Companion.ExceptionClass,
            sqlConstraintException = module.requireClass("SqlConstraintException") as ObjException.Companion.ExceptionClass,
            sqlUsageException = module.requireClass("SqlUsageException") as ObjException.Companion.ExceptionClass,
            rollbackException = module.requireClass("RollbackException") as ObjException.Companion.ExceptionClass,
            sqlTypes = SqlTypeEntries.resolve(module),
        )
    }
}

internal class SqlTypeEntries private constructor(
    private val entries: Map<String, ObjEnumEntry>,
) {
    fun require(name: String): ObjEnumEntry = entries[name]
        ?: error("lyng.io.db.SqlType entry is missing: $name")

    companion object {
        fun resolve(module: ModuleScope): SqlTypeEntries {
            val enumClass = resolveEnum(module, "SqlType")
            return SqlTypeEntries(
                listOf(
                    "Binary", "String", "Int", "Double", "Decimal",
                    "Bool", "Instant", "Date", "DateTime"
                ).associateWith { name ->
                    enumClass.byName[ObjString(name)] as? ObjEnumEntry
                        ?: error("lyng.io.db.SqlType.$name is missing")
                }
            )
        }

        private fun resolveEnum(module: ModuleScope, enumName: String): ObjEnumClass {
            val local = module.get(enumName)?.value as? ObjEnumClass
            if (local != null) return local
            val root = module.importProvider.rootScope.get(enumName)?.value as? ObjEnumClass
            return root ?: error("lyng.io.db declaration enum is missing: $enumName")
        }
    }
}

internal class SqlRuntimeTypes private constructor(
    val core: SqlCoreModule,
    val databaseClass: ObjClass,
    val transactionClass: ObjClass,
    val resultSetClass: ObjClass,
    val rowClass: ObjClass,
    val columnClass: ObjClass,
    val executionResultClass: ObjClass,
) {
    companion object {
        fun create(prefix: String, core: SqlCoreModule): SqlRuntimeTypes {
            val databaseClass = object : ObjClass("${prefix}Database", core.databaseClass) {}
            val transactionClass = object : ObjClass("${prefix}Transaction", core.transactionClass) {}
            val resultSetClass = object : ObjClass("${prefix}ResultSet", core.resultSetClass) {}
            val rowClass = object : ObjClass("${prefix}Row", core.rowClass) {}
            val columnClass = object : ObjClass("${prefix}Column", core.columnClass) {}
            val executionResultClass = object : ObjClass("${prefix}ExecutionResult", core.executionResultClass) {}
            val runtime = SqlRuntimeTypes(
                core = core,
                databaseClass = databaseClass,
                transactionClass = transactionClass,
                resultSetClass = resultSetClass,
                rowClass = rowClass,
                columnClass = columnClass,
                executionResultClass = executionResultClass,
            )
            runtime.bind()
            return runtime
        }
    }

    private fun bind() {
        databaseClass.addFn("close") {
            thisAs<SqlDatabaseObj>().backend.close()
            ObjNull
        }

        databaseClass.addFn("transaction") {
            val self = thisAs<SqlDatabaseObj>()
            val block = args.list.getOrNull(0) ?: raiseError("Expected exactly 1 argument, got ${args.list.size}")
            if (!block.isInstanceOf("Callable")) {
                raiseClassCastError("transaction block must be callable")
            }
            self.backend.transaction(this) { backend ->
                val lifetime = SqlTransactionLifetime(this@SqlRuntimeTypes.core)
                try {
                    call(block, Arguments(SqlTransactionObj(this@SqlRuntimeTypes, backend, lifetime)), ObjNull)
                } finally {
                    lifetime.close()
                }
            }
        }

        transactionClass.addFn("select") {
            val self = thisAs<SqlTransactionObj>()
            self.lifetime.ensureActive(this)
            val clause = (args.list.getOrNull(0) as? ObjString)?.value
                ?: raiseClassCastError("query must be String")
            val params = args.list.drop(1)
            SqlResultSetObj(self.types, self.lifetime, self.backend.select(this, clause, params))
        }
        transactionClass.addFn("execute") {
            val self = thisAs<SqlTransactionObj>()
            self.lifetime.ensureActive(this)
            val clause = (args.list.getOrNull(0) as? ObjString)?.value
                ?: raiseClassCastError("query must be String")
            val params = args.list.drop(1)
            SqlExecutionResultObj(self.types, self.lifetime, self.backend.execute(this, clause, params))
        }
        transactionClass.addFn("transaction") {
            val self = thisAs<SqlTransactionObj>()
            self.lifetime.ensureActive(this)
            val block = args.list.getOrNull(0) ?: raiseError("Expected exactly 1 argument, got ${args.list.size}")
            if (!block.isInstanceOf("Callable")) {
                raiseClassCastError("transaction block must be callable")
            }
            self.backend.transaction(this) { backend ->
                val lifetime = SqlTransactionLifetime(this@SqlRuntimeTypes.core)
                try {
                    call(block, Arguments(SqlTransactionObj(self.types, backend, lifetime)), ObjNull)
                } finally {
                    lifetime.close()
                }
            }
        }

        resultSetClass.addProperty("columns", getter = {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.columns)
        })
        resultSetClass.addFn("size") {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjInt.of(self.rows.size.toLong())
        }
        resultSetClass.addFn("isEmpty") {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjBool(self.rows.isEmpty())
        }
        resultSetClass.addFn("iterator") {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.rows).invokeInstanceMethod(requireScope(), "iterator")
        }
        resultSetClass.addFn("toList") {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            ObjImmutableList(self.rows)
        }

        rowClass.addProperty("size", getter = {
            val self = thisAs<SqlRowObj>()
            ObjInt.of(self.values.size.toLong())
        })
        rowClass.addProperty("values", getter = {
            val self = thisAs<SqlRowObj>()
            ObjImmutableList(self.values)
        })

        columnClass.addProperty("name", getter = { ObjString(thisAs<SqlColumnObj>().meta.name) })
        columnClass.addProperty("sqlType", getter = { thisAs<SqlColumnObj>().meta.sqlType })
        columnClass.addProperty("nullable", getter = { ObjBool(thisAs<SqlColumnObj>().meta.nullable) })
        columnClass.addProperty("nativeType", getter = { ObjString(thisAs<SqlColumnObj>().meta.nativeType) })

        executionResultClass.addProperty("affectedRowsCount", getter = {
            val self = thisAs<SqlExecutionResultObj>()
            self.lifetime.ensureActive(this)
            ObjInt.of(self.result.affectedRowsCount.toLong())
        })
        executionResultClass.addFn("getGeneratedKeys") {
            val self = thisAs<SqlExecutionResultObj>()
            self.lifetime.ensureActive(this)
            SqlResultSetObj(self.types, self.lifetime, self.result.generatedKeys)
        }
    }
}

internal class SqlTransactionLifetime(
    private val core: SqlCoreModule,
) {
    private var active = true

    fun close() {
        active = false
    }

    fun ensureActive(scope: ScopeFacade) {
        if (!active) {
            scope.raiseError(
                ObjException(core.sqlUsageException, scope.requireScope(), ObjString("SQL result can be used only while its transaction is active"))
            )
        }
    }
}

internal class SqlDatabaseObj(
    val types: SqlRuntimeTypes,
    val backend: SqlDatabaseBackend,
) : Obj() {
    override val objClass: ObjClass
        get() = types.databaseClass
}

internal class SqlTransactionObj(
    val types: SqlRuntimeTypes,
    val backend: SqlTransactionBackend,
    val lifetime: SqlTransactionLifetime,
) : Obj() {
    override val objClass: ObjClass
        get() = types.transactionClass
}

internal class SqlResultSetObj(
    val types: SqlRuntimeTypes,
    val lifetime: SqlTransactionLifetime,
    data: SqlResultSetData,
) : Obj() {
    val columns: List<Obj> = data.columns.map { SqlColumnObj(types, it) }
    val rows: List<Obj> = buildRows(types, data)

    override val objClass: ObjClass
        get() = types.resultSetClass

    private fun buildRows(
        types: SqlRuntimeTypes,
        data: SqlResultSetData,
    ): List<Obj> {
        val indexByName = linkedMapOf<String, MutableList<Int>>()
        data.columns.forEachIndexed { index, column ->
            indexByName.getOrPut(column.name.lowercase()) { mutableListOf() }.add(index)
        }
        return data.rows.map { rowValues ->
            SqlRowObj(types, rowValues, indexByName)
        }
    }
}

internal class SqlRowObj(
    val types: SqlRuntimeTypes,
    val values: List<Obj>,
    private val indexByName: Map<String, List<Int>>,
) : Obj() {
    override val objClass: ObjClass
        get() = types.rowClass

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        return when (index) {
            is ObjInt -> {
                val idx = index.value.toInt()
                if (idx !in values.indices) {
                    scope.raiseIndexOutOfBounds("SQL row index $idx is out of bounds")
                }
                values[idx]
            }
            is ObjString -> {
                val matches = indexByName[index.value.lowercase()]
                    ?: scope.raiseError(
                        ObjException(
                            types.core.sqlUsageException,
                            scope,
                            ObjString("No such SQL result column: ${index.value}")
                        )
                    )
                if (matches.size != 1) {
                    scope.raiseError(
                        ObjException(
                            types.core.sqlUsageException,
                            scope,
                            ObjString("Ambiguous SQL result column: ${index.value}")
                        )
                    )
                }
                values[matches.first()]
            }
            else -> scope.raiseClassCastError("SQL row index must be Int or String")
        }
    }
}

internal class SqlColumnObj(
    val types: SqlRuntimeTypes,
    val meta: SqlColumnMeta,
) : Obj() {
    override val objClass: ObjClass
        get() = types.columnClass
}

internal class SqlExecutionResultObj(
    val types: SqlRuntimeTypes,
    val lifetime: SqlTransactionLifetime,
    val result: SqlExecutionResultData,
) : Obj() {
    override val objClass: ObjClass
        get() = types.executionResultClass
}
