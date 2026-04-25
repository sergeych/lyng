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
import net.sergeych.lyng.obj.ObjDateTime
import net.sergeych.lyng.obj.ObjInstant
import net.sergeych.lyng.obj.ObjReal
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
            val prepared = prepareSqlClause(requireScope(), self.types, clause, args.list.drop(1))
            SqlResultSetObj(self.types, self.lifetime, self.backend.select(this, prepared.clause, prepared.params))
        }
        transactionClass.addFn("execute") {
            val self = thisAs<SqlTransactionObj>()
            self.lifetime.ensureActive(this)
            val clause = (args.list.getOrNull(0) as? ObjString)?.value
                ?: raiseClassCastError("query must be String")
            val prepared = prepareSqlClause(requireScope(), self.types, clause, args.list.drop(1))
            SqlExecutionResultObj(self.types, self.lifetime, self.backend.execute(this, prepared.clause, prepared.params))
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
    val explicitEncoding = findExplicitDbEncodingAnnotation(scope, types, annotations)
    if (explicitEncoding != null) {
        return decodeExplicitDbEncoding(scope, types, row, column, value, targetType, explicitEncoding)
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

private fun findExplicitDbEncodingAnnotation(
    scope: Scope,
    types: SqlRuntimeTypes,
    annotations: List<DeclAnnotation>,
): DeclAnnotation? {
    val matches = annotations.filter { it.name == "DbJson" || it.name == "DbLynon" || it.name == "DbSerializeWith" }
    if (matches.size > 1) {
        raiseSqlUsage(scope, types, "Only one of @DbJson, @DbLynon, or @DbSerializeWith(...) is allowed per declaration")
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
    annotationName: String = annotation.name,
): Obj {
    if (annotation.named.isNotEmpty() || annotation.positional.size != 1) {
        raiseSqlUsage(scope, types, "@$annotationName(...) expects exactly one adapter instance argument")
    }
    val adapter = annotation.positional.first()
    if (!adapter.isInstanceOf(types.core.dbFieldAdapterClass)) {
        raiseSqlUsage(scope, types, "@$annotationName(...) argument must implement DbFieldAdapter")
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
            "Failed to decode SQL column '${column.name}' with @$annotationName(...): ${e.message ?: e::class.simpleName}"
        )
    }
}

private suspend fun decodeExplicitDbEncoding(
    scope: Scope,
    types: SqlRuntimeTypes,
    row: SqlRowObj,
    column: SqlColumnMeta,
    value: Obj,
    targetType: TypeDecl,
    annotation: DeclAnnotation,
): Obj {
    when (annotation.name) {
        "DbJson", "DbLynon" -> if (annotation.positional.isNotEmpty() || annotation.named.isNotEmpty()) {
            raiseSqlUsage(scope, types, "@${annotation.name} does not take arguments")
        }
    }
    if (value === ObjNull) {
        if (targetType.isNullable || targetType == TypeDecl.TypeNullableAny) return ObjNull
        raiseSqlUsage(scope, types, "SQL column '${column.name}' is null but target type ${renderTypeName(targetType)} is non-null")
    }
    return when (annotation.name) {
        "DbJson" -> {
            val text = value as? ObjString
                ?: raiseSqlUsage(scope, types, "@DbJson expects SQL column '${column.name}' to be String, got ${value.objClass.className}")
            try {
                ObjJsonClass.decodeFromJsonElement(scope, Json.parseToJsonElement(text.value), targetType)
            } catch (e: Throwable) {
                raiseSqlUsage(
                    scope,
                    types,
                    "Failed to decode JSON column '${column.name}' as ${renderTypeName(targetType)}: ${e.message ?: e::class.simpleName}"
                )
            }
        }
        "DbLynon" -> {
            val payload = value as? ObjBuffer
                ?: raiseSqlUsage(scope, types, "@DbLynon expects SQL column '${column.name}' to be Binary, got ${value.objClass.className}")
            try {
                val decoded = ObjLynonClass.decodeAny(scope, ObjBitBuffer(BitArray(payload.byteArray, 8)))
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
        "DbSerializeWith" -> {
            val adapted = applyDbFieldAdapter(scope, types, row, column, value, targetType, annotation, "DbSerializeWith")
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
            adapted
        }
        else -> raiseSqlUsage(scope, types, "Unsupported DB field decoding annotation: @${annotation.name}")
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

private data class PreparedSqlClause(
    val clause: String,
    val params: List<Obj>,
)

private data class SqlProjectionField(
    val name: String,
    val value: Obj,
    val targetType: TypeDecl,
    val annotations: List<DeclAnnotation>,
)

private data class SqlProjectionFilter(
    val excludedFields: Set<String> = emptySet(),
)

private enum class SqlMacroKind {
    Cols,
    Vals,
    Set
}

private suspend fun prepareSqlClause(
    scope: Scope,
    types: SqlRuntimeTypes,
    clause: String,
    params: List<Obj>,
): PreparedSqlClause {
    if (!clause.contains("@")) return PreparedSqlClause(clause, params)

    val explicitRefs = findExplicitSqlArgumentRefs(scope, types, clause)
    val output = StringBuilder(clause.length + 32)
    val boundParams = mutableListOf<Obj>()
    var cursor = 0
    var legacySequentialIndex = 0
    var sawMacro = false

    while (cursor < clause.length) {
        val macro = parseSqlMacroAt(scope, types, clause, cursor)
        if (macro != null) {
            sawMacro = true
            val projection = buildSqlProjection(scope, types, params, macro.paramIndex, macro.filter)
            output.append(
                when (macro.kind) {
                    SqlMacroKind.Cols -> projection.joinToString(", ") { it.name }
                    SqlMacroKind.Vals -> projection.joinToString(", ") { "?" }
                    SqlMacroKind.Set -> projection.joinToString(", ") { "${it.name} = ?" }
                }
            )
            if (macro.kind != SqlMacroKind.Cols) {
                for (field in projection) {
                    boundParams += encodeProjectedDbField(scope, types, field)
                }
            }
            cursor = macro.endExclusive
            continue
        }

        val indexed = parseIndexedPlaceholderAt(clause, cursor)
        if (indexed != null) {
            output.append('?')
            boundParams += resolveSqlArgument(scope, types, params, indexed.paramIndex)
            cursor = indexed.endExclusive
            continue
        }

        val ch = clause[cursor]
        if (ch == '\'') {
            val end = skipSqlSingleQuotedString(clause, cursor)
            output.append(clause, cursor, end)
            cursor = end
            continue
        }
        if (ch == '"') {
            val end = skipSqlDoubleQuotedIdentifier(clause, cursor)
            output.append(clause, cursor, end)
            cursor = end
            continue
        }
        if (ch == '-' && cursor + 1 < clause.length && clause[cursor + 1] == '-') {
            val end = skipSqlLineComment(clause, cursor)
            output.append(clause, cursor, end)
            cursor = end
            continue
        }
        if (ch == '/' && cursor + 1 < clause.length && clause[cursor + 1] == '*') {
            val end = skipSqlBlockComment(clause, cursor)
            output.append(clause, cursor, end)
            cursor = end
            continue
        }
        if (ch == '?') {
            if (sawMacro) {
                raiseSqlUsage(
                    scope,
                    types,
                    "SQL clauses using @cols/@vals/@set must use explicit indexed placeholders like ?1, ?2 for non-expanded parameters"
                )
            }
            output.append('?')
            if (legacySequentialIndex >= params.size) {
                raiseSqlUsage(scope, types, "SQL parameter count mismatch: statement expects more values than provided")
            }
            boundParams += params[legacySequentialIndex++]
            cursor++
            continue
        }
        output.append(ch)
        cursor++
    }

    if (!sawMacro) return PreparedSqlClause(clause, params)

    val unreferencedIndexed = (1..params.size).filter { it !in explicitRefs }
    if (unreferencedIndexed.isNotEmpty()) {
        raiseSqlUsage(
            scope,
            types,
            "Unused SQL argument(s) in macro clause: ${unreferencedIndexed.joinToString(", ") { "?$it" }}"
        )
    }
    return PreparedSqlClause(output.toString(), boundParams)
}

private fun findExplicitSqlArgumentRefs(
    scope: Scope,
    types: SqlRuntimeTypes,
    clause: String,
): Set<Int> {
    val refs = linkedSetOf<Int>()
    var cursor = 0
    while (cursor < clause.length) {
        val macro = parseSqlMacroAt(scope, types, clause, cursor)
        if (macro != null) {
            refs += macro.paramIndex
            cursor = macro.endExclusive
            continue
        }
        val indexed = parseIndexedPlaceholderAt(clause, cursor)
        if (indexed != null) {
            refs += indexed.paramIndex
            cursor = indexed.endExclusive
            continue
        }
        val ch = clause[cursor]
        cursor = when {
            ch == '\'' -> skipSqlSingleQuotedString(clause, cursor)
            ch == '"' -> skipSqlDoubleQuotedIdentifier(clause, cursor)
            ch == '-' && cursor + 1 < clause.length && clause[cursor + 1] == '-' -> skipSqlLineComment(clause, cursor)
            ch == '/' && cursor + 1 < clause.length && clause[cursor + 1] == '*' -> skipSqlBlockComment(clause, cursor)
            else -> cursor + 1
        }
    }
    return refs
}

private data class ParsedSqlMacro(
    val kind: SqlMacroKind,
    val paramIndex: Int,
    val filter: SqlProjectionFilter,
    val endExclusive: Int,
)

private data class ParsedIndexedPlaceholder(
    val paramIndex: Int,
    val endExclusive: Int,
)

private fun parseSqlMacroAt(
    scope: Scope,
    types: SqlRuntimeTypes,
    clause: String,
    start: Int,
): ParsedSqlMacro? {
    if (clause[start] != '@') return null
    val kinds = listOf(
        "cols" to SqlMacroKind.Cols,
        "vals" to SqlMacroKind.Vals,
        "set" to SqlMacroKind.Set,
    )
    for ((name, kind) in kinds) {
        if (!clause.startsWith("@$name", start)) continue
        var cursor = start + name.length + 1
        while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
        if (cursor >= clause.length || clause[cursor] != '(') {
            raiseSqlUsage(scope, types, "Malformed SQL macro @$name: expected '('")
        }
        cursor++
        while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
        if (cursor >= clause.length || clause[cursor] != '?') {
            raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): expected indexed argument like ?1")
        }
        cursor++
        val numberStart = cursor
        while (cursor < clause.length && clause[cursor].isDigit()) cursor++
        if (numberStart == cursor) {
            raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): expected indexed argument like ?1")
        }
        val paramIndex = clause.substring(numberStart, cursor).toInt()
        val excludedFields = linkedSetOf<String>()
        while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
        if (cursor < clause.length && clause.startsWith("except", cursor)) {
            val afterKeyword = cursor + "except".length
            if (afterKeyword < clause.length && isSqlMacroFilterIdentifierPart(clause[afterKeyword])) {
                raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): expected ')' or 'except:'")
            }
            cursor = afterKeyword
            while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
            if (cursor >= clause.length || clause[cursor] != ':') {
                raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): expected 'except:'")
            }
            cursor++
            while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
            var parsedAny = false
            while (cursor < clause.length) {
                val parsedField = parseSqlMacroFilterFieldName(clause, cursor) ?: break
                excludedFields += parsedField.name.lowercase()
                cursor = parsedField.endExclusive
                parsedAny = true
                while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
                if (cursor < clause.length && clause[cursor] == ',') {
                    cursor++
                    while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
                    continue
                }
                break
            }
            if (!parsedAny) {
                raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): 'except:' must list at least one field name")
            }
        }
        while (cursor < clause.length && clause[cursor].isWhitespace()) cursor++
        if (cursor >= clause.length || clause[cursor] != ')') {
            raiseSqlUsage(scope, types, "Malformed SQL macro @$name(...): expected ')'")
        }
        return ParsedSqlMacro(kind, paramIndex, SqlProjectionFilter(excludedFields), cursor + 1)
    }
    return null
}

private fun parseIndexedPlaceholderAt(clause: String, start: Int): ParsedIndexedPlaceholder? {
    if (clause[start] != '?') return null
    if (start + 1 >= clause.length || !clause[start + 1].isDigit()) return null
    var cursor = start + 1
    while (cursor < clause.length && clause[cursor].isDigit()) cursor++
    return ParsedIndexedPlaceholder(clause.substring(start + 1, cursor).toInt(), cursor)
}

private fun isSqlMacroFilterIdentifierStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

private fun isSqlMacroFilterIdentifierPart(ch: Char): Boolean =
    ch == '_' || ch.isLetterOrDigit()

private data class ParsedSqlMacroFilterField(
    val name: String,
    val endExclusive: Int,
)

private fun parseSqlMacroFilterFieldName(clause: String, start: Int): ParsedSqlMacroFilterField? {
    if (start >= clause.length) return null
    val quote = clause[start]
    if (quote == '"' || quote == '\'') {
        var cursor = start + 1
        val out = StringBuilder()
        while (cursor < clause.length) {
            val ch = clause[cursor]
            if (ch == quote) {
                if (cursor + 1 < clause.length && clause[cursor + 1] == quote) {
                    out.append(quote)
                    cursor += 2
                    continue
                }
                return ParsedSqlMacroFilterField(out.toString(), cursor + 1)
            }
            out.append(ch)
            cursor++
        }
        return null
    }
    if (!isSqlMacroFilterIdentifierStart(clause[start])) return null
    var cursor = start + 1
    while (cursor < clause.length && isSqlMacroFilterIdentifierPart(clause[cursor])) cursor++
    return ParsedSqlMacroFilterField(clause.substring(start until cursor), cursor)
}

private fun skipSqlSingleQuotedString(clause: String, start: Int): Int {
    var cursor = start + 1
    while (cursor < clause.length) {
        if (clause[cursor] == '\'') {
            if (cursor + 1 < clause.length && clause[cursor + 1] == '\'') {
                cursor += 2
                continue
            }
            return cursor + 1
        }
        cursor++
    }
    return clause.length
}

private fun skipSqlDoubleQuotedIdentifier(clause: String, start: Int): Int {
    var cursor = start + 1
    while (cursor < clause.length) {
        if (clause[cursor] == '"') {
            if (cursor + 1 < clause.length && clause[cursor + 1] == '"') {
                cursor += 2
                continue
            }
            return cursor + 1
        }
        cursor++
    }
    return clause.length
}

private fun skipSqlLineComment(clause: String, start: Int): Int {
    var cursor = start + 2
    while (cursor < clause.length && clause[cursor] != '\n') cursor++
    return cursor
}

private fun skipSqlBlockComment(clause: String, start: Int): Int {
    var cursor = start + 2
    while (cursor + 1 < clause.length) {
        if (clause[cursor] == '*' && clause[cursor + 1] == '/') return cursor + 2
        cursor++
    }
    return clause.length
}

private fun resolveSqlArgument(
    scope: Scope,
    types: SqlRuntimeTypes,
    params: List<Obj>,
    oneBasedIndex: Int,
): Obj {
    if (oneBasedIndex <= 0 || oneBasedIndex > params.size) {
        raiseSqlUsage(scope, types, "SQL parameter reference ?$oneBasedIndex is out of range for ${params.size} argument(s)")
    }
    return params[oneBasedIndex - 1]
}

private suspend fun buildSqlProjection(
    scope: Scope,
    types: SqlRuntimeTypes,
    params: List<Obj>,
    oneBasedIndex: Int,
    filter: SqlProjectionFilter = SqlProjectionFilter(),
): List<SqlProjectionField> {
    val source = resolveSqlArgument(scope, types, params, oneBasedIndex)
    val instance = source as? ObjInstance
        ?: raiseSqlUsage(scope, types, "SQL object expansion expects argument ?$oneBasedIndex to be an object instance, got ${source.objClass.className}")

    val projected = mutableListOf<SqlProjectionField>()
    val seen = linkedSetOf<String>()
    val meta = instance.objClass.constructorMeta
    if (meta != null) {
        for (param in meta.params) {
            if (param.isTransient || hasDbExceptAnnotation(param.annotations)) continue
            if (!seen.add(param.name.lowercase())) {
                raiseSqlUsage(scope, types, "Ambiguous SQL projection field '${param.name}' in ${instance.objClass.className}")
            }
            projected += SqlProjectionField(
                name = param.name,
                value = instance.readField(scope, param.name).value,
                targetType = param.type,
                annotations = param.annotations
            )
        }
    }
    for ((storageName, record) in instance.serializingVars) {
        val name = storageName.substringAfterLast("::")
        val annotations = instance.objClass.getInstanceMemberOrNull(name)?.annotations ?: emptyList()
        if (hasDbExceptAnnotation(annotations)) continue
        if (!seen.add(name.lowercase())) {
            raiseSqlUsage(scope, types, "Ambiguous SQL projection field '$name' in ${instance.objClass.className}")
        }
        projected += SqlProjectionField(
            name = name,
            value = record.value,
            targetType = record.typeDecl ?: TypeDecl.TypeAny,
            annotations = annotations
        )
    }
    if (projected.isEmpty()) {
        raiseSqlUsage(scope, types, "SQL object expansion for ${instance.objClass.className} produced no projected fields")
    }
    if (filter.excludedFields.isEmpty()) {
        return projected
    }
    val unknownFields = filter.excludedFields.filter { excluded ->
        projected.none { it.name.lowercase() == excluded }
    }
    if (unknownFields.isNotEmpty()) {
        raiseSqlUsage(
            scope,
            types,
            "SQL object expansion for ${instance.objClass.className} can't exclude unknown field(s): ${unknownFields.joinToString(", ")}"
        )
    }
    val filtered = projected.filter { it.name.lowercase() !in filter.excludedFields }
    if (filtered.isEmpty()) {
        raiseSqlUsage(scope, types, "SQL object expansion for ${instance.objClass.className} produced no projected fields after except:")
    }
    return filtered
}

private fun hasDbExceptAnnotation(annotations: List<DeclAnnotation>): Boolean =
    annotations.any { it.name == "DbExcept" }

private suspend fun encodeProjectedDbField(
    scope: Scope,
    types: SqlRuntimeTypes,
    field: SqlProjectionField,
): Obj {
    if (field.value === ObjNull) return ObjNull
    val explicit = findExplicitDbEncodingAnnotation(scope, types, field.annotations)
    if (explicit != null) {
        return encodeExplicitDbField(scope, types, field, explicit)
    }
    if (isDirectDbBindable(field.value)) return field.value
    raiseSqlUsage(
        scope,
        types,
        "Field '${field.name}' of ${field.value.objClass.className} requires explicit DB serialization policy (@DbJson, @DbLynon, or @DbSerializeWith(...))"
    )
}

private suspend fun encodeExplicitDbField(
    scope: Scope,
    types: SqlRuntimeTypes,
    field: SqlProjectionField,
    annotation: DeclAnnotation,
): Obj {
    return when (annotation.name) {
        "DbJson" -> {
            if (annotation.positional.isNotEmpty() || annotation.named.isNotEmpty()) {
                raiseSqlUsage(scope, types, "@DbJson does not take arguments")
            }
            ObjString(ObjJsonClass.encodeToJsonElement(scope, field.value, field.targetType).toString())
        }
        "DbLynon" -> {
            if (annotation.positional.isNotEmpty() || annotation.named.isNotEmpty()) {
                raiseSqlUsage(scope, types, "@DbLynon does not take arguments")
            }
            ObjBuffer(ObjLynonClass.encodeAny(scope, field.value).bitArray.asUByteArray())
        }
        "DbSerializeWith" -> encodeWithDbFieldAdapter(scope, types, field, annotation)
        else -> raiseSqlUsage(scope, types, "Unsupported DB field encoding annotation: @${annotation.name}")
    }
}

private suspend fun encodeWithDbFieldAdapter(
    scope: Scope,
    types: SqlRuntimeTypes,
    field: SqlProjectionField,
    annotation: DeclAnnotation,
): Obj {
    if (annotation.named.isNotEmpty() || annotation.positional.size != 1) {
        raiseSqlUsage(scope, types, "@DbSerializeWith(...) expects exactly one adapter instance argument")
    }
    val adapter = annotation.positional.first()
    if (!adapter.isInstanceOf(types.core.dbFieldAdapterClass)) {
        raiseSqlUsage(scope, types, "@DbSerializeWith(...) argument must implement DbFieldAdapter")
    }
    val encoded = try {
        adapter.invokeInstanceMethod(
            scope,
            "encode",
            Arguments(field.value, runtimeTargetTypeObject(scope, field.targetType))
        )
    } catch (e: Throwable) {
        raiseSqlUsage(
            scope,
            types,
            "Failed to encode SQL field '${field.name}' with @DbSerializeWith(...): ${e.message ?: e::class.simpleName}"
        )
    }
    if (!isDirectDbBindable(encoded)) {
        raiseSqlUsage(
            scope,
            types,
            "@DbSerializeWith(...) for field '${field.name}' must return a direct DB-bindable value, got ${encoded.objClass.className}"
        )
    }
    return encoded
}

private fun isDirectDbBindable(value: Obj): Boolean = when (value) {
    ObjNull -> true
    is ObjBool, is ObjInt, is ObjReal, is ObjString, is ObjBuffer, is ObjInstant, is ObjDateTime -> true
    else -> when (value.objClass.className) {
        "Date", "Decimal" -> true
        else -> false
    }
}
