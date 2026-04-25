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
import net.sergeych.lyng.DeclAnnotation
import net.sergeych.lyng.ModuleScope
import net.sergeych.lyng.Scope
import net.sergeych.lyng.ScopeFacade
import net.sergeych.lyng.TypeDecl
import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjBool
import net.sergeych.lyng.obj.ObjBuffer
import net.sergeych.lyng.obj.ObjBitBuffer
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjEnumClass
import net.sergeych.lyng.obj.ObjEnumEntry
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjImmutableList
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInt
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjRecord
import net.sergeych.lyng.obj.ObjString
import net.sergeych.lyng.obj.ObjTypeExpr
import net.sergeych.lyng.obj.ObjVoid
import net.sergeych.lyng.obj.thisAs
import net.sergeych.lyng.requireScope
import net.sergeych.lyng.serialization.ObjJsonClass
import net.sergeych.lynon.BitArray
import net.sergeych.lynon.ObjLynonClass
import kotlinx.serialization.json.Json
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
import kotlin.collections.LinkedHashMap
import kotlin.collections.linkedMapOf
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.set
import kotlin.text.lowercase
import kotlin.text.substringAfterLast

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
    val iterableClass: ObjClass,
    val iteratorClass: ObjClass,
    val dbFieldAdapterClass: ObjClass,
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
            iterableClass = module.importProvider.rootScope.get("Iterable")?.value as? ObjClass
                ?: error("lyng.stdlib.Iterable declaration is missing"),
            iteratorClass = module.importProvider.rootScope.get("Iterator")?.value as? ObjClass
                ?: error("lyng.stdlib.Iterator declaration is missing"),
            dbFieldAdapterClass = module.requireClass("DbFieldAdapter"),
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
    val decodedIterableClass: ObjClass,
    val decodedIteratorClass: ObjClass,
) {
    companion object {
        fun create(prefix: String, core: SqlCoreModule): SqlRuntimeTypes {
            val databaseClass = object : ObjClass("${prefix}Database", core.databaseClass) {}
            val transactionClass = object : ObjClass("${prefix}Transaction", core.transactionClass) {}
            val resultSetClass = object : ObjClass("${prefix}ResultSet", core.resultSetClass) {}
            val rowClass = object : ObjClass("${prefix}Row", core.rowClass) {}
            val columnClass = object : ObjClass("${prefix}Column", core.columnClass) {}
            val executionResultClass = object : ObjClass("${prefix}ExecutionResult", core.executionResultClass) {}
            val decodedIterableClass = object : ObjClass("${prefix}DecodedIterable", core.iterableClass) {}
            val decodedIteratorClass = object : ObjClass("${prefix}DecodedIterator", core.iteratorClass) {}
            val runtime = SqlRuntimeTypes(
                core = core,
                databaseClass = databaseClass,
                transactionClass = transactionClass,
                resultSetClass = resultSetClass,
                rowClass = rowClass,
                columnClass = columnClass,
                executionResultClass = executionResultClass,
                decodedIterableClass = decodedIterableClass,
                decodedIteratorClass = decodedIteratorClass,
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
        resultSetClass.addFn(
            "decodeAs",
            callSignature = core.resultSetClass.getInstanceMemberOrNull("decodeAs")?.callSignature
        ) {
            val self = thisAs<SqlResultSetObj>()
            self.lifetime.ensureActive(this)
            SqlDecodedIterableObj(
                self.types,
                self.lifetime,
                self.rows.map { it as SqlRowObj },
                resolveDecodeTargetType(requireScope())
            )
        }

        rowClass.addProperty("size", getter = {
            val self = thisAs<SqlRowObj>()
            ObjInt.of(self.values.size.toLong())
        })
        rowClass.addProperty("values", getter = {
            val self = thisAs<SqlRowObj>()
            ObjImmutableList(self.values)
        })
        rowClass.addFn(
            "decodeAs",
            callSignature = core.rowClass.getInstanceMemberOrNull("decodeAs")?.callSignature
        ) {
            decodeSqlRow(requireScope(), thisAs(), resolveDecodeTargetType(requireScope()))
        }

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

        decodedIterableClass.addFn("iterator") {
            val self = thisAs<SqlDecodedIterableObj>()
            self.lifetime.ensureActive(this)
            SqlDecodedIteratorObj(self.types, self.lifetime, self.rows.iterator(), self.targetType)
        }

        decodedIteratorClass.addFn("hasNext") {
            val self = thisAs<SqlDecodedIteratorObj>()
            self.lifetime.ensureActive(this)
            ObjBool(self.rows.hasNext())
        }
        decodedIteratorClass.addFn("next") {
            val self = thisAs<SqlDecodedIteratorObj>()
            self.lifetime.ensureActive(this)
            decodeSqlRow(requireScope(), self.rows.next(), self.targetType)
        }
        decodedIteratorClass.addFn("cancelIteration") {
            thisAs<SqlDecodedIteratorObj>().lifetime.ensureActive(this)
            ObjVoid
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
    val columnMeta: List<SqlColumnMeta> = data.columns
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
            SqlRowObj(types, data.columns, rowValues, indexByName)
        }
    }
}

internal class SqlRowObj(
    val types: SqlRuntimeTypes,
    val columns: List<SqlColumnMeta>,
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

internal class SqlDecodedIterableObj(
    val types: SqlRuntimeTypes,
    val lifetime: SqlTransactionLifetime,
    val rows: List<SqlRowObj>,
    val targetType: TypeDecl,
) : Obj() {
    override val objClass: ObjClass
        get() = types.decodedIterableClass
}

internal class SqlDecodedIteratorObj(
    val types: SqlRuntimeTypes,
    val lifetime: SqlTransactionLifetime,
    val rows: Iterator<SqlRowObj>,
    val targetType: TypeDecl,
) : Obj() {
    override val objClass: ObjClass
        get() = types.decodedIteratorClass
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

private fun resolveDecodeTargetType(scope: Scope): TypeDecl {
    val explicit = scope.args.explicitTypeArgs.singleOrNull()
    if (explicit != null) return explicit
    val bound = scope["T"]?.value
    return when (bound) {
        is ObjTypeExpr -> bound.typeDecl
        is ObjClass -> TypeDecl.Simple(bound.className, false)
        else -> scope.raiseIllegalArgument("decodeAs requires exactly one type argument")
    }
}

private suspend fun decodeSqlRow(scope: Scope, row: SqlRowObj, targetType: TypeDecl): Obj {
    val targetClass = resolveTypeDeclClass(scope, targetType)
    if (targetClass != null && shouldUseStructuredRowDecoding(row, targetClass)) {
        return decodeStructuredRow(scope, row, targetType, targetClass)
    }
    if (row.values.size == 1) {
        return decodeSqlValue(scope, row.types, row, row.columns[0], row.values[0], targetType)
    }
    scope.raiseError(
        ObjException(
            row.types.core.sqlUsageException,
            scope,
            ObjString("Can't decode SQL row with ${row.values.size} columns as ${renderTypeName(targetType)}")
        )
    )
}

private fun shouldUseStructuredRowDecoding(row: SqlRowObj, targetClass: ObjClass): Boolean {
    if (row.values.size > 1) return true
    val memberNames = linkedMapOf<String, Int>()
    targetClass.constructorMeta?.params?.forEach { memberNames[it.name.lowercase()] = 1 }
    row.columns.forEach { column ->
        if (column.name.lowercase() in memberNames) return true
    }
    return false
}

private suspend fun decodeStructuredRow(
    scope: Scope,
    row: SqlRowObj,
    targetType: TypeDecl,
    targetClass: ObjClass,
): Obj {
    val meta = targetClass.constructorMeta
        ?: raiseSqlUsage(scope, row.types, "Can't decode SQL row as ${targetClass.className}: target class has no constructor metadata")

    val normalizedColumns = buildColumnLookup(scope, row)
    val consumed = mutableSetOf<String>()
    val namedArgs = LinkedHashMap<String, Obj>()

    for (param in meta.params) {
        if (param.isTransient) continue
        val lowered = param.name.lowercase()
        val column = normalizedColumns[lowered]
        if (column == null) {
            if (param.defaultValue == null && !param.type.isNullable) {
                raiseSqlUsage(scope, row.types, "Missing SQL column '${param.name}' for ${targetClass.className}")
            }
            continue
        }
        namedArgs[param.name] = decodeSqlValue(
            scope,
            row.types,
            row,
            column.first,
            row.values[column.second],
            param.type,
            param.annotations
        )
        consumed += lowered
    }

    val callScope = scope.createChildScope(args = Arguments(list = emptyList(), named = namedArgs))
    val instance = targetClass.callOn(callScope)
    if (instance !is ObjInstance) {
        return instance
    }
    val knownFields = collectSerializableFieldTargets(scope, row, targetClass, instance)

    for ((lowered, target) in knownFields) {
        if (consumed.contains(lowered)) continue
        val column = normalizedColumns[lowered] ?: continue
        val targetTypeDecl = target.record.typeDecl ?: TypeDecl.TypeAny
        target.record.value = decodeSqlValue(
            scope,
            row.types,
            row,
            column.first,
            row.values[column.second],
            targetTypeDecl,
            target.annotations
        )
        consumed += lowered
    }

    val allowedNames = meta.params.filter { !it.isTransient }.map { it.name.lowercase() }.toMutableSet()
    allowedNames += knownFields.keys
    for (column in row.columns) {
        val lowered = column.name.lowercase()
        if (lowered !in allowedNames) {
            raiseSqlUsage(scope, row.types, "Unknown SQL result column '${column.name}' while decoding ${renderTypeName(targetType)}")
        }
    }

    instance.invokeInstanceMethod(scope, "onDeserialized", Arguments.EMPTY) { ObjVoid }
    return instance
}

private data class FieldDecodeTarget(
    val record: ObjRecord,
    val annotations: List<DeclAnnotation>,
)

private fun collectSerializableFieldTargets(
    scope: Scope,
    row: SqlRowObj,
    targetClass: ObjClass,
    instance: ObjInstance,
): Map<String, FieldDecodeTarget> {
    val result = linkedMapOf<String, FieldDecodeTarget>()
    for ((name, record) in instance.serializingVars) {
        val simpleName = name.substringAfterLast("::")
        val lowered = simpleName.lowercase()
        val classAnnotations = targetClass.getInstanceMemberOrNull(simpleName)?.annotations ?: emptyList()
        val previous = result.put(lowered, FieldDecodeTarget(record, classAnnotations))
        if (previous != null) {
            raiseSqlUsage(scope, row.types, "Ambiguous serializable target field '$lowered' in ${targetClass.className}")
        }
    }
    return result
}

private fun buildColumnLookup(scope: Scope, row: SqlRowObj): Map<String, Pair<SqlColumnMeta, Int>> {
    val result = linkedMapOf<String, Pair<SqlColumnMeta, Int>>()
    row.columns.forEachIndexed { index, column ->
        val lowered = column.name.lowercase()
        if (result.containsKey(lowered)) {
            raiseSqlUsage(scope, row.types, "Ambiguous SQL result column: ${column.name}")
        }
        result[lowered] = column to index
    }
    return result
}

private suspend fun decodeSqlValue(
    scope: Scope,
    types: SqlRuntimeTypes,
    row: SqlRowObj,
    column: SqlColumnMeta,
    value: Obj,
    targetType: TypeDecl,
    annotations: List<DeclAnnotation> = emptyList(),
): Obj {
    val adapterAnnotation = findDbDecodeWithAnnotation(scope, types, annotations)
    if (adapterAnnotation != null) {
        val adapted = applyDbFieldAdapter(scope, types, row, column, value, targetType, adapterAnnotation)
        if (adapted === ObjNull) {
            if (targetType.isNullable || targetType == TypeDecl.TypeNullableAny) return ObjNull
            raiseSqlUsage(scope, types, "SQL column '${column.name}' is null but target type ${renderTypeName(targetType)} is non-null")
        }
        if (!matchesTypeDeclCompat(scope, adapted, targetType)) {
            raiseSqlUsage(
                scope,
                types,
                "DB adapter result for column '${column.name}' does not match target type ${renderTypeName(targetType)}"
            )
        }
        return adapted
    }
    if (value === ObjNull) {
        if (targetType.isNullable || targetType == TypeDecl.TypeNullableAny) return ObjNull
        raiseSqlUsage(scope, types, "SQL column '${column.name}' is null but target type ${renderTypeName(targetType)} is non-null")
    }
    if (matchesTypeDeclCompat(scope, value, targetType)) {
        return value
    }
    if (value is ObjBuffer) {
        return try {
            val decoded = ObjLynonClass.decodeAny(scope, ObjBitBuffer(BitArray(value.byteArray, 8)))
            if (!matchesTypeDeclCompat(scope, decoded, targetType)) {
                raiseSqlUsage(
                    scope,
                    types,
                    "Lynon-decoded SQL column '${column.name}' does not match target type ${renderTypeName(targetType)}"
                )
            }
            decoded
        } catch (e: Throwable) {
            raiseSqlUsage(
                scope,
                types,
                "Failed to decode Lynon column '${column.name}' as ${renderTypeName(targetType)}: ${e.message ?: e::class.simpleName}"
            )
        }
    }
    if (isJsonLikeNativeType(column.nativeType) && value is ObjString) {
        return try {
            ObjJsonClass.decodeFromJsonElement(scope, Json.parseToJsonElement(value.value), targetType)
        } catch (e: Throwable) {
            raiseSqlUsage(
                scope,
                types,
                "Failed to decode JSON column '${column.name}' as ${renderTypeName(targetType)}: ${e.message ?: e::class.simpleName}"
            )
        }
    }
    raiseSqlUsage(
        scope,
        types,
        "SQL column '${column.name}' of native type ${column.nativeType} can't be decoded as ${renderTypeName(targetType)}"
    )
}

private fun findDbDecodeWithAnnotation(
    scope: Scope,
    types: SqlRuntimeTypes,
    annotations: List<DeclAnnotation>,
): DeclAnnotation? {
    val matches = annotations.filter { it.name == "DbDecodeWith" }
    if (matches.size > 1) {
        raiseSqlUsage(scope, types, "Only one @DbDecodeWith(...) annotation is allowed per declaration")
    }
    return matches.singleOrNull()
}

private suspend fun applyDbFieldAdapter(
    scope: Scope,
    types: SqlRuntimeTypes,
    row: SqlRowObj,
    column: SqlColumnMeta,
    value: Obj,
    targetType: TypeDecl,
    annotation: DeclAnnotation,
): Obj {
    if (annotation.named.isNotEmpty() || annotation.positional.size != 1) {
        raiseSqlUsage(scope, types, "@DbDecodeWith(...) expects exactly one adapter instance argument")
    }
    val adapter = annotation.positional.first()
    if (!adapter.isInstanceOf(types.core.dbFieldAdapterClass)) {
        raiseSqlUsage(scope, types, "@DbDecodeWith(...) argument must implement DbFieldAdapter")
    }
    return try {
        adapter.invokeInstanceMethod(
            scope,
            "decode",
            Arguments(
                value,
                SqlColumnObj(types, column),
                row,
                runtimeTargetTypeObject(scope, targetType)
            )
        )
    } catch (e: Throwable) {
        raiseSqlUsage(
            scope,
            types,
            "Failed to decode SQL column '${column.name}' with @DbDecodeWith(...): ${e.message ?: e::class.simpleName}"
        )
    }
}

private fun runtimeTargetTypeObject(scope: Scope, targetType: TypeDecl): Obj {
    return resolveTypeDeclClass(scope, targetType) ?: ObjTypeExpr(targetType)
}

private fun isJsonLikeNativeType(nativeType: String): Boolean {
    val normalized = nativeType.trim().substringBefore('(').lowercase()
    return normalized == "json" || normalized == "jsonb"
}

private fun resolveTypeDeclClass(scope: Scope, type: TypeDecl): ObjClass? = when (type) {
    is TypeDecl.Simple -> {
        val direct = scope[type.name]?.value as? ObjClass
        direct ?: scope[type.name.substringAfterLast('.')]?.value as? ObjClass
    }
    is TypeDecl.Generic -> {
        val direct = scope[type.name]?.value as? ObjClass
        direct ?: scope[type.name.substringAfterLast('.')]?.value as? ObjClass
    }
    is TypeDecl.Ellipsis -> resolveTypeDeclClass(scope, type.elementType)
    is TypeDecl.TypeVar -> when (val bound = scope[type.name]?.value) {
        is ObjClass -> bound
        is ObjTypeExpr -> resolveTypeDeclClass(scope, bound.typeDecl)
        else -> null
    }
    else -> null
}

private fun matchesTypeDeclCompat(scope: Scope, value: Obj, typeDecl: TypeDecl): Boolean {
    if (value === ObjNull) return typeDecl.isNullable || typeDecl == TypeDecl.TypeNullableAny
    fun resolve(typeName: String): ObjClass? {
        val direct = scope[typeName]?.value as? ObjClass
        return direct ?: scope[typeName.substringAfterLast('.')]?.value as? ObjClass
    }
    return when (typeDecl) {
        TypeDecl.TypeAny, TypeDecl.TypeNullableAny -> true
        is TypeDecl.TypeVar -> {
            val cls = resolve(typeDecl.name)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name)
        }
        is TypeDecl.Simple -> {
            val cls = resolve(typeDecl.name)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name.substringAfterLast('.'))
        }
        is TypeDecl.Generic -> {
            val cls = resolve(typeDecl.name)
            if (cls != null) value.isInstanceOf(cls) else value.isInstanceOf(typeDecl.name.substringAfterLast('.'))
        }
        is TypeDecl.Function -> value.isInstanceOf("Callable")
        is TypeDecl.Ellipsis -> matchesTypeDeclCompat(scope, value, typeDecl.elementType)
        is TypeDecl.Union -> typeDecl.options.any { matchesTypeDeclCompat(scope, value, it) }
        is TypeDecl.Intersection -> typeDecl.options.all { matchesTypeDeclCompat(scope, value, it) }
    }
}

private fun renderTypeName(type: TypeDecl): String = when (type) {
    TypeDecl.TypeAny -> "Object"
    TypeDecl.TypeNullableAny -> "Object?"
    is TypeDecl.Simple -> type.name + if (type.isNullable) "?" else ""
    is TypeDecl.Generic -> type.name + "<" + type.args.joinToString(",") { renderTypeName(it) } + ">" + if (type.isNullable) "?" else ""
    is TypeDecl.Function -> "Callable"
    is TypeDecl.Ellipsis -> renderTypeName(type.elementType) + "..."
    is TypeDecl.TypeVar -> type.name + if (type.isNullable) "?" else ""
    is TypeDecl.Union -> type.options.joinToString(" | ") { renderTypeName(it) } + if (type.isNullable) "?" else ""
    is TypeDecl.Intersection -> type.options.joinToString(" & ") { renderTypeName(it) } + if (type.isNullable) "?" else ""
}

private fun raiseSqlUsage(scope: Scope, types: SqlRuntimeTypes?, message: String): Nothing {
    val exClass = types?.core?.sqlUsageException
    if (exClass != null) {
        scope.raiseError(ObjException(exClass, scope, ObjString(message)))
    }
    scope.raiseIllegalArgument(message)
}
