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

package net.sergeych.lyng.bytecode

import net.sergeych.lyng.*
import net.sergeych.lyng.obj.*

class BytecodeCompiler(
    private val allowLocalSlots: Boolean = true,
    private val returnLabels: Set<String> = emptySet(),
    private val rangeLocalNames: Set<String> = emptySet(),
    private val allowedScopeNames: Set<String>? = null,
    private val moduleScopeId: Int? = null,
    private val slotTypeByScopeId: Map<Int, Map<Int, ObjClass>> = emptyMap(),
    private val knownNameObjClass: Map<String, ObjClass> = emptyMap(),
) {
    private var builder = CmdBuilder()
    private var nextSlot = 0
    private var nextAddrSlot = 0
    private var scopeSlotCount = 0
    private var scopeSlotIndices = IntArray(0)
    private var scopeSlotNames = emptyArray<String?>()
    private var scopeSlotIsModule = BooleanArray(0)
    private var scopeSlotMutables = BooleanArray(0)
    private val scopeSlotMap = LinkedHashMap<ScopeSlotKey, Int>()
    private val scopeSlotNameMap = LinkedHashMap<ScopeSlotKey, String>()
    private val scopeSlotMutableMap = LinkedHashMap<ScopeSlotKey, Boolean>()
    private val scopeSlotIndexByName = LinkedHashMap<String, Int>()
    private val pendingScopeNameRefs = LinkedHashSet<String>()
    private val addrSlotByScopeSlot = LinkedHashMap<Int, Int>()
    private data class LocalSlotInfo(val name: String, val isMutable: Boolean)
    private val localSlotInfoMap = LinkedHashMap<ScopeSlotKey, LocalSlotInfo>()
    private val localSlotIndexByKey = LinkedHashMap<ScopeSlotKey, Int>()
    private val localSlotIndexByName = LinkedHashMap<String, Int>()
    private val loopSlotOverrides = LinkedHashMap<String, Int>()
    private var localSlotNames = emptyArray<String?>()
    private var localSlotMutables = BooleanArray(0)
    private val declaredLocalKeys = LinkedHashSet<ScopeSlotKey>()
    private val localRangeRefs = LinkedHashMap<ScopeSlotKey, RangeRef>()
    private val slotTypes = mutableMapOf<Int, SlotType>()
    private val slotObjClass = mutableMapOf<Int, ObjClass>()
    private val nameObjClass = mutableMapOf<String, ObjClass>()
    private val slotInitClassByKey = mutableMapOf<ScopeSlotKey, ObjClass>()
    private val intLoopVarNames = LinkedHashSet<String>()
    private val loopStack = ArrayDeque<LoopContext>()
    private var forceScopeSlots = false
    private var currentPos: Pos? = null

    private data class LoopContext(
        val label: String?,
        val breakLabel: CmdBuilder.Label,
        val continueLabel: CmdBuilder.Label,
        val breakFlagSlot: Int,
        val resultSlot: Int?,
        val hasIterator: Boolean,
    )

    fun compileStatement(name: String, stmt: net.sergeych.lyng.Statement): CmdFunction? {
        prepareCompilation(stmt)
        setPos(stmt.pos)
        return when (stmt) {
            is ExpressionStatement -> compileExpression(name, stmt)
            is net.sergeych.lyng.IfStatement -> compileIf(name, stmt)
            is net.sergeych.lyng.ForInStatement -> compileForIn(name, stmt)
            is net.sergeych.lyng.DoWhileStatement -> compileDoWhile(name, stmt)
            is net.sergeych.lyng.WhileStatement -> compileWhile(name, stmt)
            is net.sergeych.lyng.WhenStatement -> {
                val value = compileWhen(stmt, true) ?: return null
                builder.emit(Opcode.RET, value.slot)
                val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
                builder.build(
                    name,
                    localCount,
                    addrCount = nextAddrSlot,
                    returnLabels = returnLabels,
                    scopeSlotIndices,
                    scopeSlotNames,
                    scopeSlotIsModule,
                    localSlotNames,
                    localSlotMutables
                )
            }
            is BlockStatement -> compileBlock(name, stmt)
            is net.sergeych.lyng.InlineBlockStatement -> compileInlineBlock(name, stmt)
            is VarDeclStatement -> compileVarDecl(name, stmt)
            is DelegatedVarDeclStatement -> {
                val value = emitDelegatedVarDecl(stmt) ?: return null
                builder.emit(Opcode.RET, value.slot)
                val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
                builder.build(
                    name,
                    localCount,
                    addrCount = nextAddrSlot,
                    returnLabels = returnLabels,
                    scopeSlotIndices,
                    scopeSlotNames,
                    scopeSlotIsModule,
                    localSlotNames,
                    localSlotMutables
                )
            }
            is DestructuringVarDeclStatement -> {
                val value = emitDestructuringVarDecl(stmt) ?: return null
                builder.emit(Opcode.RET, value.slot)
                val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
                builder.build(
                    name,
                    localCount,
                    addrCount = nextAddrSlot,
                    returnLabels = returnLabels,
                    scopeSlotIndices,
                    scopeSlotNames,
                    scopeSlotIsModule,
                    localSlotNames,
                    localSlotMutables
                )
            }
            is net.sergeych.lyng.ThrowStatement -> compileThrowStatement(name, stmt)
            is net.sergeych.lyng.ExtensionPropertyDeclStatement -> compileExtensionPropertyDecl(name, stmt)
            is net.sergeych.lyng.TryStatement -> {
                val value = emitTry(stmt, true) ?: return null
                builder.emit(Opcode.RET, value.slot)
                val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
                builder.build(
                    name,
                    localCount,
                    addrCount = nextAddrSlot,
                    returnLabels = returnLabels,
                    scopeSlotIndices,
                    scopeSlotNames,
                    scopeSlotIsModule,
                    localSlotNames,
                    localSlotMutables
                )
            }
            else -> null
        }
    }

    private fun compileThrowStatement(name: String, stmt: net.sergeych.lyng.ThrowStatement): CmdFunction? {
        prepareCompilation(stmt)
        compileThrow(stmt) ?: return null
        return builder.build(
            name,
            localCount = nextSlot - scopeSlotCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileExtensionPropertyDecl(
        name: String,
        stmt: net.sergeych.lyng.ExtensionPropertyDeclStatement,
    ): CmdFunction? {
        prepareCompilation(stmt)
        val value = emitExtensionPropertyDecl(stmt)
        builder.emit(Opcode.RET, value.slot)
        val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    fun compileExpression(name: String, stmt: ExpressionStatement): CmdFunction? {
        prepareCompilation(stmt)
        val value = compileRefWithFallback(stmt.ref, null, stmt.pos) ?: return null
        builder.emit(Opcode.RET, value.slot)
        val localCount = maxOf(nextSlot, value.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private data class CompiledValue(val slot: Int, val type: SlotType)

    private fun allocSlot(): Int = nextSlot++

    private fun encodeMemberId(receiverClass: ObjClass, id: Int?): Int? {
        if (id == null) return null
        if (receiverClass == ObjClassType) return -(id + 2)
        return id
    }

    private fun compileRef(ref: ObjRef): CompiledValue? {
        return when (ref) {
            is ConstRef -> compileConst(ref.constValue)
            is TypeDeclRef -> compileConst(ObjTypeExpr(ref.decl()))
            is IncDecRef -> compileIncDec(ref, true)
            is CastRef -> compileCast(ref)
            is LocalSlotRef -> {
                if (ref.name == "this") {
                    return compileThisRef()
                }
                loopSlotOverrides[ref.name]?.let { slot ->
                    val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                    return CompiledValue(slot, resolved)
                }
                if (!allowLocalSlots) return null
                if (ref.isDelegated) return compileEvalRef(ref)
                if (ref.name.isEmpty()) return null
                if (ref.captureOwnerScopeId == null && refScopeId(ref) == 0) {
                    val byName = scopeSlotIndexByName[ref.name]
                    if (byName != null) {
                        val resolved = slotTypes[byName] ?: SlotType.UNKNOWN
                        return CompiledValue(byName, resolved)
                    }
                }
                val mapped = resolveSlot(ref) ?: return null
                var resolved = slotTypes[mapped] ?: SlotType.UNKNOWN
                if (resolved == SlotType.UNKNOWN && intLoopVarNames.contains(ref.name)) {
                    updateSlotType(mapped, SlotType.INT)
                    resolved = SlotType.INT
                }
                if (mapped < scopeSlotCount && resolved != SlotType.UNKNOWN) {
                    val addrSlot = ensureScopeAddr(mapped)
                    val local = allocSlot()
                    emitLoadFromAddr(addrSlot, local, resolved)
                    updateSlotType(local, resolved)
                    return CompiledValue(local, resolved)
                }
                CompiledValue(mapped, resolved)
            }
            is LocalVarRef -> {
                if (ref.name == "this") {
                    return compileThisRef()
                }
                loopSlotOverrides[ref.name]?.let { slot ->
                    val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                    return CompiledValue(slot, resolved)
                }
                if (allowLocalSlots) {
                    if (!forceScopeSlots) {
                    scopeSlotIndexByName[ref.name]?.let { slot ->
                        val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                        return CompiledValue(slot, resolved)
                    }
                    val localIndex = localSlotIndexByName[ref.name]
                    if (localIndex != null) {
                        val slot = scopeSlotCount + localIndex
                        val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                        return CompiledValue(slot, resolved)
                    }
                    }
                    if (forceScopeSlots) {
                        scopeSlotIndexByName[ref.name]?.let { slot ->
                            val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                            return CompiledValue(slot, resolved)
                        }
                    }
                }
                null
            }
            is ValueFnRef -> compileValueFnRef(ref)
            is ListLiteralRef -> compileListLiteral(ref)
            is MapLiteralRef -> compileMapLiteral(ref)
            is ThisMethodSlotCallRef -> compileThisMethodSlotCall(ref)
            is StatementRef -> {
                val compiled = compileStatementValueOrFallback(ref.statement)
                compiled ?: throw BytecodeCompileException(
                    "Unsupported StatementRef(${ref.statement::class.simpleName})",
                    Pos.builtIn
                )
            }
            is BinaryOpRef -> compileBinary(ref) ?: compileEvalRef(ref)
            is UnaryOpRef -> compileUnary(ref)
            is LogicalAndRef -> compileLogicalAnd(ref)
            is LogicalOrRef -> compileLogicalOr(ref)
            is AssignRef -> compileAssign(ref) ?: compileEvalRef(ref)
            is AssignOpRef -> compileAssignOp(ref) ?: compileEvalRef(ref)
            is AssignIfNullRef -> compileAssignIfNull(ref)
            is RangeRef -> compileRangeRef(ref)
            is ConditionalRef -> compileConditional(ref)
            is ElvisRef -> compileElvis(ref)
            is CallRef -> compileCall(ref)
            is MethodCallRef -> compileMethodCall(ref)
            is FieldRef -> compileFieldRef(ref)
            is ClassScopeMemberRef -> compileClassScopeMemberRef(ref)
            is ThisFieldSlotRef -> compileThisFieldSlotRef(ref)
            is QualifiedThisFieldSlotRef -> compileQualifiedThisFieldSlotRef(ref)
            is ImplicitThisMemberRef -> {
                val receiver = ref.preferredThisTypeName()?.let { typeName ->
                    compileThisVariantRef(typeName) ?: return null
                } ?: compileThisRef()
                val fieldId = ref.fieldId ?: -1
                val methodId = ref.methodId ?: -1
                if (fieldId < 0 && methodId < 0) {
                    val typeName = ref.preferredThisTypeName()
                        ?: throw BytecodeCompileException("Missing member id for ${ref.name}", Pos.builtIn)
                    val wrapperName = extensionPropertyGetterName(typeName, ref.name)
                    val callee = resolveDirectNameSlot(wrapperName) ?: throw BytecodeCompileException(
                        "Missing extension wrapper for ${typeName}.${ref.name}",
                        Pos.builtIn
                    )
                    val dst = allocSlot()
                    val calleeObj = ensureObjSlot(callee)
                    val args = compileCallArgsWithReceiver(receiver, emptyList(), false) ?: return null
                    val encodedCount = encodeCallArgCount(args) ?: return null
                    builder.emit(Opcode.CALL_SLOT, calleeObj.slot, args.base, encodedCount, dst)
                    updateSlotType(dst, SlotType.OBJ)
                    return CompiledValue(dst, SlotType.OBJ)
                }
                val slot = allocSlot()
                builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            is ImplicitThisMethodCallRef -> compileImplicitThisMethodCall(ref)
            is QualifiedThisMethodSlotCallRef -> compileQualifiedThisMethodSlotCall(ref)
            is IndexRef -> compileIndexRef(ref)
            else -> null
        }
    }

    private fun compileImplicitThisMethodCall(ref: ImplicitThisMethodCallRef): CompiledValue? {
        val callPos = ref.pos()
        val receiver = ref.preferredThisTypeName()?.let { typeName ->
            compileThisVariantRef(typeName) ?: return null
        } ?: compileThisRef()
        val methodId = ref.slotId()
        val dst = allocSlot()
        if (methodId != null) {
            if (!ref.optionalInvoke()) {
                val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
                val encodedCount = encodeCallArgCount(args) ?: return null
                setPos(callPos)
                builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
                return CompiledValue(dst, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val typeName = ref.preferredThisTypeName() ?: throw BytecodeCompileException(
            "Missing member id for ${ref.methodName()}",
            Pos.builtIn
        )
        val wrapperName = extensionCallableName(typeName, ref.methodName())
        val callee = resolveDirectNameSlot(wrapperName) ?: throw BytecodeCompileException(
            "Missing extension wrapper for ${typeName}.${ref.methodName()}",
            Pos.builtIn
        )
        val calleeObj = ensureObjSlot(callee)
        if (!ref.optionalInvoke()) {
            val args = compileCallArgsWithReceiver(receiver, ref.arguments(), ref.hasTailBlock()) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_SLOT, calleeObj.slot, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgsWithReceiver(receiver, ref.arguments(), ref.hasTailBlock()) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        setPos(callPos)
        builder.emit(Opcode.CALL_SLOT, calleeObj.slot, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileThisRef(): CompiledValue {
        val slot = allocSlot()
        builder.emit(Opcode.LOAD_THIS, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun compileThisVariantRef(typeName: String): CompiledValue? {
        val typeId = builder.addConst(BytecodeConst.StringVal(typeName))
        if (typeId > 0xFFFF) return null
        val slot = allocSlot()
        builder.emit(Opcode.LOAD_THIS_VARIANT, typeId, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun compileConst(obj: Obj): CompiledValue? {
        val slot = allocSlot()
        when (obj) {
            is ObjInt -> {
                val id = builder.addConst(BytecodeConst.IntVal(obj.value))
                builder.emit(Opcode.CONST_INT, id, slot)
                return CompiledValue(slot, SlotType.INT)
            }
            is ObjReal -> {
                val id = builder.addConst(BytecodeConst.RealVal(obj.value))
                builder.emit(Opcode.CONST_REAL, id, slot)
                return CompiledValue(slot, SlotType.REAL)
            }
            is ObjBool -> {
                val id = builder.addConst(BytecodeConst.Bool(obj.value))
                builder.emit(Opcode.CONST_BOOL, id, slot)
                return CompiledValue(slot, SlotType.BOOL)
            }
            is ObjString -> {
                val id = builder.addConst(BytecodeConst.StringVal(obj.value))
                builder.emit(Opcode.CONST_OBJ, id, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
            ObjNull -> {
                builder.emit(Opcode.CONST_NULL, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
            else -> {
                val id = builder.addConst(BytecodeConst.ObjRef(obj))
                builder.emit(Opcode.CONST_OBJ, id, slot)
                return CompiledValue(slot, SlotType.OBJ)
            }
        }
    }

    private fun compileValueFnRef(ref: ValueFnRef): CompiledValue? {
        val id = builder.addConst(BytecodeConst.ValueFn(ref.valueFn()))
        val slot = allocSlot()
        builder.emit(Opcode.MAKE_VALUE_FN, id, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun compileEvalRef(ref: ObjRef): CompiledValue? {
        val refInfo = when (ref) {
            is BinaryOpRef -> "BinaryOpRef(${ref.op})"
            is UnaryOpRef -> "UnaryOpRef(${ref.op})"
            else -> ref::class.simpleName ?: "UnknownRef"
        }
        throw BytecodeCompileException("Unsupported expression ($refInfo)", Pos.builtIn)
    }

    private fun compileListLiteral(ref: ListLiteralRef): CompiledValue? {
        val entries = ref.entries()
        val count = entries.size
        val baseSlot = nextSlot
        val entrySlots = IntArray(count) { allocSlot() }
        val spreads = ArrayList<Boolean>(count)
        for ((index, entry) in entries.withIndex()) {
            val value = when (entry) {
                is net.sergeych.lyng.ListEntry.Element ->
                    compileRefWithFallback(entry.ref, null, Pos.builtIn)
                is net.sergeych.lyng.ListEntry.Spread ->
                    compileRefWithFallback(entry.ref, null, Pos.builtIn)
            } ?: return null
            emitMove(value, entrySlots[index])
            spreads.add(entry is net.sergeych.lyng.ListEntry.Spread)
        }
        val planId = builder.addConst(BytecodeConst.ListLiteralPlan(spreads))
        val dst = allocSlot()
        builder.emit(Opcode.LIST_LITERAL, planId, baseSlot, count, dst)
        updateSlotType(dst, SlotType.OBJ)
        slotObjClass[dst] = ObjList.type
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileMapLiteral(ref: MapLiteralRef): CompiledValue? {
        val mapClassId = builder.addConst(BytecodeConst.ObjRef(ObjMap.type))
        val mapClassSlot = allocSlot()
        builder.emit(Opcode.CONST_OBJ, mapClassId, mapClassSlot)
        val dst = allocSlot()
        builder.emit(Opcode.CALL_SLOT, mapClassSlot, 0, 0, dst)
        updateSlotType(dst, SlotType.OBJ)
        slotObjClass[dst] = ObjMap.type
        for (entry in ref.entries()) {
            when (entry) {
                is net.sergeych.lyng.obj.MapLiteralEntry.Named -> {
                    val keyId = builder.addConst(BytecodeConst.StringVal(entry.key))
                    val keySlot = allocSlot()
                    builder.emit(Opcode.CONST_OBJ, keyId, keySlot)
                    val value = compileRefWithFallback(entry.value, null, Pos.builtIn) ?: return null
                    builder.emit(Opcode.SET_INDEX, dst, keySlot, value.slot)
                }
                is net.sergeych.lyng.obj.MapLiteralEntry.Spread -> {
                    if (entry.ref is ListLiteralRef) {
                        throw BytecodeCompileException(
                            "spread element in map literal must be a Map",
                            Pos.builtIn
                        )
                    }
                    val value = compileRefWithFallback(entry.ref, null, Pos.builtIn) ?: return null
                    val mapClassId = builder.addConst(BytecodeConst.ObjRef(ObjMap.type))
                    val mapClassSlot = allocSlot()
                    builder.emit(Opcode.CONST_OBJ, mapClassId, mapClassSlot)
                    val checkSlot = allocSlot()
                    builder.emit(Opcode.CHECK_IS, value.slot, mapClassSlot, checkSlot)
                    val okLabel = builder.label()
                    val endLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(checkSlot), CmdBuilder.Operand.LabelRef(okLabel))
                    )
                    val msgId = builder.addConst(BytecodeConst.StringVal("spread element in map literal must be a Map"))
                    val msgSlot = allocSlot()
                    builder.emit(Opcode.CONST_OBJ, msgId, msgSlot)
                    val posId = builder.addConst(BytecodeConst.PosVal(Pos.builtIn))
                    builder.emit(Opcode.THROW, posId, msgSlot)
                    builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
                    builder.mark(okLabel)
                    val mergedSlot = allocSlot()
                    builder.emit(Opcode.ADD_OBJ, dst, value.slot, mergedSlot)
                    builder.emit(Opcode.MOVE_OBJ, mergedSlot, dst)
                    builder.mark(endLabel)
                }
            }
        }
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileCast(ref: CastRef): CompiledValue? {
        val value = compileRefWithFallback(ref.castValueRef(), null, ref.castPos()) ?: return null
        val typeValue = compileRefWithFallback(ref.castTypeRef(), null, ref.castPos()) ?: return null
        val objValue = ensureObjSlot(value)
        val typeObj = ensureObjSlot(typeValue)
        if (!ref.castIsNullable()) {
            builder.emit(Opcode.ASSERT_IS, objValue.slot, typeObj.slot)
            val resultSlot = allocSlot()
            builder.emit(Opcode.MAKE_QUALIFIED_VIEW, objValue.slot, typeObj.slot, resultSlot)
            updateSlotType(resultSlot, SlotType.OBJ)
            return CompiledValue(resultSlot, SlotType.OBJ)
        }
        val checkSlot = allocSlot()
        builder.emit(Opcode.CHECK_IS, objValue.slot, typeObj.slot, checkSlot)
        updateSlotType(checkSlot, SlotType.BOOL)
        val resultSlot = allocSlot()
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val okLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(checkSlot), CmdBuilder.Operand.LabelRef(okLabel))
        )
        builder.emit(Opcode.MOVE_OBJ, nullSlot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(okLabel)
        builder.emit(Opcode.MAKE_QUALIFIED_VIEW, objValue.slot, typeObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileUnary(ref: UnaryOpRef): CompiledValue? {
        val a = compileRef(unaryOperand(ref)) ?: return null
        val out = allocSlot()
        return when (unaryOp(ref)) {
            UnaryOp.NEGATE -> when (a.type) {
                SlotType.INT -> {
                    builder.emit(Opcode.NEG_INT, a.slot, out)
                    CompiledValue(out, SlotType.INT)
                }
                SlotType.REAL -> {
                    builder.emit(Opcode.NEG_REAL, a.slot, out)
                    CompiledValue(out, SlotType.REAL)
                }
                else -> compileObjUnaryOp(unaryOperand(ref), a, "negate", Pos.builtIn)
            }
            UnaryOp.NOT -> {
                when (a.type) {
                    SlotType.BOOL -> builder.emit(Opcode.NOT_BOOL, a.slot, out)
                    SlotType.INT -> {
                        val tmp = allocSlot()
                        builder.emit(Opcode.INT_TO_BOOL, a.slot, tmp)
                        builder.emit(Opcode.NOT_BOOL, tmp, out)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> {
                        val objSlot = ensureObjSlot(a)
                        val tmp = allocSlot()
                        builder.emit(Opcode.OBJ_TO_BOOL, objSlot.slot, tmp)
                        builder.emit(Opcode.NOT_BOOL, tmp, out)
                        updateSlotType(tmp, SlotType.BOOL)
                    }
                    else -> return null
                }
                CompiledValue(out, SlotType.BOOL)
            }
            UnaryOp.BITNOT -> {
                if (a.type == SlotType.INT) {
                    builder.emit(Opcode.INV_INT, a.slot, out)
                    return CompiledValue(out, SlotType.INT)
                }
                return compileObjUnaryOp(unaryOperand(ref), a, "bitNot", Pos.builtIn)
            }
        }
    }

    private fun compileObjUnaryOp(
        ref: ObjRef,
        value: CompiledValue,
        memberName: String,
        pos: Pos
    ): CompiledValue? {
        val receiverClass = resolveReceiverClass(ref)
        val methodId = receiverClass?.instanceMethodIdMap(includeAbstract = true)?.get(memberName)
        if (methodId != null) {
            val receiverObj = ensureObjSlot(value)
            val dst = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiverObj.slot, methodId, 0, 0, dst)
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        if (receiverClass == null && memberName == "negate") {
            val zeroId = builder.addConst(BytecodeConst.IntVal(0))
            val zeroSlot = allocSlot()
            builder.emit(Opcode.CONST_INT, zeroId, zeroSlot)
            updateSlotType(zeroSlot, SlotType.INT)
            val obj = ensureObjSlot(value)
            val dst = allocSlot()
            builder.emit(Opcode.SUB_OBJ, zeroSlot, obj.slot, dst)
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        if (memberName == "negate" && receiverClass in setOf(ObjInt.type, ObjReal.type)) {
            val zeroId = builder.addConst(BytecodeConst.IntVal(0))
            val zeroSlot = allocSlot()
            builder.emit(Opcode.CONST_INT, zeroId, zeroSlot)
            updateSlotType(zeroSlot, SlotType.INT)
            val obj = ensureObjSlot(value)
            val dst = allocSlot()
            builder.emit(Opcode.SUB_OBJ, zeroSlot, obj.slot, dst)
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        throw BytecodeCompileException(
            "Unknown member $memberName on ${receiverClass?.className ?: "unknown"}",
            pos
        )
    }

    private fun operatorMemberName(op: BinOp): String? = when (op) {
        BinOp.PLUS -> "plus"
        BinOp.MINUS -> "minus"
        BinOp.STAR -> "mul"
        BinOp.SLASH -> "div"
        BinOp.PERCENT -> "mod"
        BinOp.BAND -> "bitAnd"
        BinOp.BOR -> "bitOr"
        BinOp.BXOR -> "bitXor"
        BinOp.SHL -> "shl"
        BinOp.SHR -> "shr"
        else -> null
    }

    private fun allowKotlinOperatorFallback(receiverClass: ObjClass, op: BinOp): Boolean = when (op) {
        BinOp.PLUS -> receiverClass in setOf(
            ObjString.type,
            ObjInt.type,
            ObjReal.type,
            ObjList.type,
            ObjSet.type,
            ObjMap.type,
            ObjBuffer.type,
            ObjInstant.type,
            ObjDateTime.type
        )
        BinOp.MINUS -> receiverClass in setOf(
            ObjInt.type,
            ObjReal.type,
            ObjSet.type,
            ObjInstant.type,
            ObjDateTime.type
        )
        BinOp.STAR -> receiverClass in setOf(ObjInt.type, ObjReal.type, ObjString.type)
        BinOp.SLASH, BinOp.PERCENT -> receiverClass in setOf(ObjInt.type, ObjReal.type)
        else -> false
    }

    private fun compileObjBinaryOp(
        leftRef: ObjRef,
        leftValue: CompiledValue,
        rightValue: CompiledValue,
        op: BinOp,
        pos: Pos
    ): CompiledValue? {
        val memberName = operatorMemberName(op) ?: return null
        val receiverClass = resolveReceiverClass(leftRef)
        if (receiverClass == null) {
            val objOpcode = when (op) {
                BinOp.PLUS -> Opcode.ADD_OBJ
                BinOp.MINUS -> Opcode.SUB_OBJ
                BinOp.STAR -> Opcode.MUL_OBJ
                BinOp.SLASH -> Opcode.DIV_OBJ
                BinOp.PERCENT -> Opcode.MOD_OBJ
                else -> null
            }
            if (objOpcode != null) {
                val receiverObj = ensureObjSlot(leftValue)
                val argObj = ensureObjSlot(rightValue)
                val dst = allocSlot()
                builder.emit(objOpcode, receiverObj.slot, argObj.slot, dst)
                updateSlotType(dst, SlotType.OBJ)
                return CompiledValue(dst, SlotType.OBJ)
            }
            throw BytecodeCompileException(
                "Operator requires compile-time receiver type: $memberName",
                pos
            )
        }
        val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[memberName]
        if (methodId != null) {
            val receiverObj = ensureObjSlot(leftValue)
            val argObj = ensureObjSlot(rightValue)
            val dst = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiverObj.slot, methodId, argObj.slot, 1, dst)
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val objOpcode = when (op) {
            BinOp.PLUS -> Opcode.ADD_OBJ
            BinOp.MINUS -> Opcode.SUB_OBJ
            BinOp.STAR -> Opcode.MUL_OBJ
            BinOp.SLASH -> Opcode.DIV_OBJ
            BinOp.PERCENT -> Opcode.MOD_OBJ
            else -> null
        }
        if (objOpcode != null && allowKotlinOperatorFallback(receiverClass, op)) {
            val receiverObj = ensureObjSlot(leftValue)
            val argObj = ensureObjSlot(rightValue)
            val dst = allocSlot()
            builder.emit(objOpcode, receiverObj.slot, argObj.slot, dst)
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        throw BytecodeCompileException(
            "Unknown member $memberName on ${receiverClass.className}",
            pos
        )
    }

    private fun compileBinary(ref: BinaryOpRef): CompiledValue? {
        val op = binaryOp(ref)
        if (op == BinOp.AND || op == BinOp.OR) {
            return compileLogical(op, binaryLeft(ref), binaryRight(ref), refPos(ref))
        }
        if (op == BinOp.EQARROW) {
            val leftValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val leftObj = ensureObjSlot(leftValue)
            val rightObj = ensureObjSlot(rightValue)
            val argBase = nextSlot
            val argLeft = allocSlot()
            emitMove(leftObj, argLeft)
            val argRight = allocSlot()
            emitMove(rightObj, argRight)
            val mapEntryClassId = builder.addConst(BytecodeConst.ObjRef(ObjMapEntry.type))
            val mapEntryClassSlot = allocSlot()
            builder.emit(Opcode.CONST_OBJ, mapEntryClassId, mapEntryClassSlot)
            val dst = allocSlot()
            builder.emit(Opcode.CALL_SLOT, mapEntryClassSlot, argBase, 2, dst)
            updateSlotType(dst, SlotType.OBJ)
            slotObjClass[dst] = ObjMapEntry.type
            return CompiledValue(dst, SlotType.OBJ)
        }
        if (op == BinOp.REF_EQ || op == BinOp.REF_NEQ) {
            val leftValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val leftObj = ensureObjSlot(leftValue)
            val rightObj = ensureObjSlot(rightValue)
            val dst = allocSlot()
            val opcode = if (op == BinOp.REF_EQ) Opcode.CMP_REF_EQ_OBJ else Opcode.CMP_REF_NEQ_OBJ
            builder.emit(opcode, leftObj.slot, rightObj.slot, dst)
            updateSlotType(dst, SlotType.BOOL)
            return CompiledValue(dst, SlotType.BOOL)
        }
        if (op == BinOp.SHUTTLE) {
            val leftValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val leftObj = ensureObjSlot(leftValue)
            val rightObj = ensureObjSlot(rightValue)
            val resultSlot = allocSlot()
            val endLabel = builder.label()

            val eqSlot = allocSlot()
            builder.emit(Opcode.CMP_EQ_OBJ, leftObj.slot, rightObj.slot, eqSlot)
            val eqLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(eqSlot), CmdBuilder.Operand.LabelRef(eqLabel))
            )

            val ltSlot = allocSlot()
            builder.emit(Opcode.CMP_LT_OBJ, leftObj.slot, rightObj.slot, ltSlot)
            val ltLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(ltSlot), CmdBuilder.Operand.LabelRef(ltLabel))
            )

            val gtSlot = allocSlot()
            builder.emit(Opcode.CMP_GT_OBJ, leftObj.slot, rightObj.slot, gtSlot)
            val gtLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(gtSlot), CmdBuilder.Operand.LabelRef(gtLabel))
            )

            val minusThreeId = builder.addConst(BytecodeConst.IntVal(-3))
            builder.emit(Opcode.CONST_INT, minusThreeId, resultSlot)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

            builder.mark(eqLabel)
            val zeroId = builder.addConst(BytecodeConst.IntVal(0))
            builder.emit(Opcode.CONST_INT, zeroId, resultSlot)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

            builder.mark(ltLabel)
            val minusOneId = builder.addConst(BytecodeConst.IntVal(-1))
            builder.emit(Opcode.CONST_INT, minusOneId, resultSlot)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

            builder.mark(gtLabel)
            val oneId = builder.addConst(BytecodeConst.IntVal(1))
            builder.emit(Opcode.CONST_INT, oneId, resultSlot)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

            builder.mark(endLabel)
            updateSlotType(resultSlot, SlotType.INT)
            return CompiledValue(resultSlot, SlotType.INT)
        }
        if (op == BinOp.IN || op == BinOp.NOTIN) {
            val leftValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val leftObj = ensureObjSlot(leftValue)
            val rightObj = ensureObjSlot(rightValue)
            val boolSlot = allocSlot()
            builder.emit(Opcode.CONTAINS_OBJ, rightObj.slot, leftObj.slot, boolSlot)
            updateSlotType(boolSlot, SlotType.BOOL)
            if (op == BinOp.NOTIN) {
                val outSlot = allocSlot()
                builder.emit(Opcode.NOT_BOOL, boolSlot, outSlot)
                updateSlotType(outSlot, SlotType.BOOL)
                return CompiledValue(outSlot, SlotType.BOOL)
            }
            return CompiledValue(boolSlot, SlotType.BOOL)
        }
        if (op == BinOp.MATCH || op == BinOp.NOTMATCH) {
            val leftRef = binaryLeft(ref)
            val rightRef = binaryRight(ref)
            val receiverClass = resolveReceiverClass(leftRef) ?: throw BytecodeCompileException(
                "Match operator requires compile-time receiver type",
                refPos(ref)
            )
            val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)["operatorMatch"]
                ?: throw BytecodeCompileException(
                    "Unknown member operatorMatch on ${receiverClass.className}",
                    refPos(ref)
                )
            val receiver = compileRefWithFallback(leftRef, null, refPos(ref)) ?: return null
            val rightValue = compileRefWithFallback(rightRef, null, refPos(ref)) ?: return null
            val receiverObj = ensureObjSlot(receiver)
            val argObj = ensureObjSlot(rightValue)
            val dst = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiverObj.slot, methodId, argObj.slot, 1, dst)
            updateSlotType(dst, SlotType.OBJ)
            val boolSlot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, dst, boolSlot)
            updateSlotType(boolSlot, SlotType.BOOL)
            if (op == BinOp.NOTMATCH) {
                val outSlot = allocSlot()
                builder.emit(Opcode.NOT_BOOL, boolSlot, outSlot)
                updateSlotType(outSlot, SlotType.BOOL)
                return CompiledValue(outSlot, SlotType.BOOL)
            }
            return CompiledValue(boolSlot, SlotType.BOOL)
        }
        if (op == BinOp.IS || op == BinOp.NOTIS) {
            val objValue = compileRefWithFallback(binaryLeft(ref), null, refPos(ref)) ?: return null
            val typeValue = compileRefWithFallback(binaryRight(ref), null, refPos(ref)) ?: return null
            val objSlot = ensureObjSlot(objValue)
            val typeSlot = ensureObjSlot(typeValue)
            val checkSlot = allocSlot()
            builder.emit(Opcode.CHECK_IS, objSlot.slot, typeSlot.slot, checkSlot)
            updateSlotType(checkSlot, SlotType.BOOL)
            if (op == BinOp.NOTIS) {
                val outSlot = allocSlot()
                builder.emit(Opcode.NOT_BOOL, checkSlot, outSlot)
                updateSlotType(outSlot, SlotType.BOOL)
                return CompiledValue(outSlot, SlotType.BOOL)
            }
            return CompiledValue(checkSlot, SlotType.BOOL)
        }
        val leftRef = binaryLeft(ref)
        val rightRef = binaryRight(ref)
        var a = compileRefWithFallback(leftRef, null, refPos(ref)) ?: return null
        var b = compileRefWithFallback(rightRef, null, refPos(ref)) ?: return null
        val intOps = setOf(
            BinOp.PLUS, BinOp.MINUS, BinOp.STAR, BinOp.SLASH, BinOp.PERCENT,
            BinOp.BAND, BinOp.BOR, BinOp.BXOR, BinOp.SHL, BinOp.SHR
        )
        val leftIsLoopVar = (leftRef as? LocalSlotRef)?.name?.let { intLoopVarNames.contains(it) } == true
        val rightIsLoopVar = (rightRef as? LocalSlotRef)?.name?.let { intLoopVarNames.contains(it) } == true
        if (a.type == SlotType.UNKNOWN && b.type == SlotType.INT && op in intOps && leftIsLoopVar) {
            updateSlotType(a.slot, SlotType.INT)
            a = CompiledValue(a.slot, SlotType.INT)
        }
        if (b.type == SlotType.UNKNOWN && a.type == SlotType.INT && op in intOps && rightIsLoopVar) {
            updateSlotType(b.slot, SlotType.INT)
            b = CompiledValue(b.slot, SlotType.INT)
        }
        if (a.type == SlotType.UNKNOWN && b.type == SlotType.UNKNOWN && op in intOps && leftIsLoopVar && rightIsLoopVar) {
            updateSlotType(a.slot, SlotType.INT)
            updateSlotType(b.slot, SlotType.INT)
            a = CompiledValue(a.slot, SlotType.INT)
            b = CompiledValue(b.slot, SlotType.INT)
        }
        val typesMismatch = a.type != b.type && a.type != SlotType.UNKNOWN && b.type != SlotType.UNKNOWN
        val allowMixedNumeric = op in setOf(BinOp.PLUS, BinOp.MINUS, BinOp.STAR, BinOp.SLASH)
        if (typesMismatch && op in setOf(BinOp.PLUS, BinOp.MINUS, BinOp.STAR, BinOp.SLASH, BinOp.PERCENT)) {
            return compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
        }
        if ((a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) &&
            op in setOf(BinOp.PLUS, BinOp.MINUS, BinOp.STAR, BinOp.SLASH, BinOp.PERCENT)
        ) {
            return compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
        }
        if (typesMismatch && !allowMixedNumeric &&
            op !in setOf(BinOp.EQ, BinOp.NEQ, BinOp.LT, BinOp.LTE, BinOp.GT, BinOp.GTE)
        ) {
            return null
        }
        val out = allocSlot()
        return when (op) {
            BinOp.PLUS -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.ADD_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.ADD_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.ADD_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.ADD_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ && b.type != SlotType.UNKNOWN) return null
                    compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                }
                else -> null
            }
            BinOp.MINUS -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.SUB_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.SUB_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.SUB_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.SUB_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ && b.type != SlotType.UNKNOWN) return null
                    compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                }
                else -> null
            }
            BinOp.STAR -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.MUL_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.MUL_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.MUL_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.MUL_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ && b.type != SlotType.UNKNOWN) return null
                    compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                }
                else -> null
            }
            BinOp.SLASH -> when (a.type) {
                SlotType.INT -> {
                    when (b.type) {
                        SlotType.INT -> {
                            builder.emit(Opcode.DIV_INT, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.INT)
                        }
                        SlotType.REAL -> compileRealArithmeticWithCoercion(Opcode.DIV_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.REAL -> {
                    when (b.type) {
                        SlotType.REAL -> {
                            builder.emit(Opcode.DIV_REAL, a.slot, b.slot, out)
                            CompiledValue(out, SlotType.REAL)
                        }
                        SlotType.INT -> compileRealArithmeticWithCoercion(Opcode.DIV_REAL, a, b, out)
                        SlotType.OBJ -> null
                        else -> null
                    }
                }
                SlotType.OBJ -> {
                    if (b.type != SlotType.OBJ && b.type != SlotType.UNKNOWN) return null
                    compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                }
                else -> null
            }
            BinOp.PERCENT -> {
                return when (a.type) {
                    SlotType.INT -> {
                        if (b.type != SlotType.INT) return null
                        builder.emit(Opcode.MOD_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ -> {
                        if (b.type != SlotType.OBJ && b.type != SlotType.UNKNOWN) return null
                        compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    }
                    else -> null
                }
            }
            BinOp.EQ -> {
                compileCompareEq(a, b, out)
            }
            BinOp.NEQ -> {
                compileCompareNeq(a, b, out)
            }
            BinOp.LT -> {
                compileCompareLt(a, b, out)
            }
            BinOp.LTE -> {
                compileCompareLte(a, b, out)
            }
            BinOp.GT -> {
                compileCompareGt(a, b, out)
            }
            BinOp.GTE -> {
                compileCompareGte(a, b, out)
            }
            BinOp.REF_EQ -> {
                if (a.type != SlotType.OBJ || b.type != SlotType.OBJ) return null
                builder.emit(Opcode.CMP_REF_EQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.REF_NEQ -> {
                if (a.type != SlotType.OBJ || b.type != SlotType.OBJ) return null
                builder.emit(Opcode.CMP_REF_NEQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.AND -> {
                if (a.type != SlotType.BOOL) return null
                builder.emit(Opcode.AND_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.OR -> {
                if (a.type != SlotType.BOOL) return null
                builder.emit(Opcode.OR_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            BinOp.BAND -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.AND_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    else -> null
                }
            }
            BinOp.BOR -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.OR_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    else -> null
                }
            }
            BinOp.BXOR -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.XOR_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    else -> null
                }
            }
            BinOp.SHL -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.SHL_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    else -> null
                }
            }
            BinOp.SHR -> {
                when (a.type) {
                    SlotType.INT -> {
                        builder.emit(Opcode.SHR_INT, a.slot, b.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.OBJ, SlotType.UNKNOWN -> compileObjBinaryOp(leftRef, a, b, op, refPos(ref))
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun compileRealArithmeticWithCoercion(
        op: Opcode,
        a: CompiledValue,
        b: CompiledValue,
        out: Int
    ): CompiledValue? {
        if (a.type == SlotType.INT && b.type == SlotType.REAL) {
            val left = allocSlot()
            builder.emit(Opcode.INT_TO_REAL, a.slot, left)
            builder.emit(op, left, b.slot, out)
            return CompiledValue(out, SlotType.REAL)
        }
        if (a.type == SlotType.REAL && b.type == SlotType.INT) {
            val right = allocSlot()
            builder.emit(Opcode.INT_TO_REAL, b.slot, right)
            builder.emit(op, a.slot, right, out)
            return CompiledValue(out, SlotType.REAL)
        }
        return null
    }

    private fun compileCompareEq(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_EQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_EQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_EQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_EQ_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.BOOL && b.type == SlotType.BOOL -> {
                builder.emit(Opcode.CMP_EQ_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_EQ_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_EQ_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_EQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareNeq(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_NEQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_NEQ_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_NEQ_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_NEQ_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.BOOL && b.type == SlotType.BOOL -> {
                builder.emit(Opcode.CMP_NEQ_BOOL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_NEQ_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_NEQ_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_NEQ_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareLt(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LT_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LT_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_LT_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareLte(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_LTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LTE_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_LTE_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_LTE_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_LTE_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareGt(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GT_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GT_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GT_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GT_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_GT_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileCompareGte(a: CompiledValue, b: CompiledValue, out: Int): CompiledValue? {
        if (a.type == SlotType.UNKNOWN || b.type == SlotType.UNKNOWN) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        if (a.type == SlotType.OBJ || b.type == SlotType.OBJ) {
            val left = ensureObjSlot(a)
            val right = ensureObjSlot(b)
            builder.emit(Opcode.CMP_GTE_OBJ, left.slot, right.slot, out)
            return CompiledValue(out, SlotType.BOOL)
        }
        return when {
            a.type == SlotType.INT && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GTE_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GTE_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.INT && b.type == SlotType.REAL -> {
                builder.emit(Opcode.CMP_GTE_INT_REAL, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.REAL && b.type == SlotType.INT -> {
                builder.emit(Opcode.CMP_GTE_REAL_INT, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            a.type == SlotType.OBJ && b.type == SlotType.OBJ -> {
                builder.emit(Opcode.CMP_GTE_OBJ, a.slot, b.slot, out)
                CompiledValue(out, SlotType.BOOL)
            }
            else -> null
        }
    }

    private fun compileLogical(op: BinOp, left: ObjRef, right: ObjRef, pos: Pos): CompiledValue? {
        val leftValue = compileRefWithFallback(left, SlotType.BOOL, pos) ?: return null
        if (leftValue.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val shortLabel = builder.label()
        val endLabel = builder.label()
        if (op == BinOp.AND) {
            builder.emit(
                Opcode.JMP_IF_FALSE,
                listOf(CmdBuilder.Operand.IntVal(leftValue.slot), CmdBuilder.Operand.LabelRef(shortLabel))
            )
        } else {
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(leftValue.slot), CmdBuilder.Operand.LabelRef(shortLabel))
            )
        }
        val rightValue = compileRefWithFallback(right, SlotType.BOOL, pos) ?: return null
        emitMove(rightValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(shortLabel)
        val constId = builder.addConst(BytecodeConst.Bool(op == BinOp.OR))
        builder.emit(Opcode.CONST_BOOL, constId, resultSlot)
        builder.mark(endLabel)
        return CompiledValue(resultSlot, SlotType.BOOL)
    }

    private fun compileLogicalAnd(ref: LogicalAndRef): CompiledValue? {
        val leftValue = compileRefWithFallback(ref.left(), SlotType.BOOL, Pos.builtIn) ?: return null
        val leftBool = if (leftValue.type == SlotType.BOOL) {
            leftValue
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, leftValue.slot, slot)
            CompiledValue(slot, SlotType.BOOL)
        }
        val resultSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, resultSlot)
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(leftBool.slot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        val rightValue = compileRefWithFallback(ref.right(), SlotType.BOOL, Pos.builtIn) ?: return null
        val rightBool = if (rightValue.type == SlotType.BOOL) {
            rightValue
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, rightValue.slot, slot)
            CompiledValue(slot, SlotType.BOOL)
        }
        builder.emit(Opcode.MOVE_BOOL, rightBool.slot, resultSlot)
        builder.mark(endLabel)
        return CompiledValue(resultSlot, SlotType.BOOL)
    }

    private fun compileLogicalOr(ref: LogicalOrRef): CompiledValue? {
        val leftValue = compileRefWithFallback(ref.left(), SlotType.BOOL, Pos.builtIn) ?: return null
        val leftBool = if (leftValue.type == SlotType.BOOL) {
            leftValue
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, leftValue.slot, slot)
            CompiledValue(slot, SlotType.BOOL)
        }
        val resultSlot = allocSlot()
        val trueId = builder.addConst(BytecodeConst.Bool(true))
        builder.emit(Opcode.CONST_BOOL, trueId, resultSlot)
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(leftBool.slot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        val rightValue = compileRefWithFallback(ref.right(), SlotType.BOOL, Pos.builtIn) ?: return null
        val rightBool = if (rightValue.type == SlotType.BOOL) {
            rightValue
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, rightValue.slot, slot)
            CompiledValue(slot, SlotType.BOOL)
        }
        builder.emit(Opcode.MOVE_BOOL, rightBool.slot, resultSlot)
        builder.mark(endLabel)
        return CompiledValue(resultSlot, SlotType.BOOL)
    }

    private fun compileAssign(ref: AssignRef): CompiledValue? {
        val localTarget = assignTarget(ref)
        if (localTarget != null) {
            if (!allowLocalSlots) return null
            val value = compileRef(assignValue(ref)) ?: return null
            if (!localTarget.isMutable || localTarget.isDelegated) {
                val msgId = builder.addConst(BytecodeConst.StringVal("can't reassign val ${localTarget.name}"))
                val msgSlot = allocSlot()
                builder.emit(Opcode.CONST_OBJ, msgId, msgSlot)
                val posId = builder.addConst(BytecodeConst.PosVal(localTarget.pos()))
                builder.emit(Opcode.THROW, posId, msgSlot)
                return value
            }
            val resolvedSlot = resolveSlot(localTarget)
                ?: resolveAssignableSlotByName(localTarget.name)?.first
                ?: return null
            val slot = if (resolvedSlot < scopeSlotCount && localTarget.captureOwnerScopeId == null) {
                localSlotIndexByName[localTarget.name]?.let { scopeSlotCount + it } ?: resolvedSlot
            } else {
                resolvedSlot
            }
            if (slot < scopeSlotCount && value.type != SlotType.UNKNOWN) {
                val addrSlot = ensureScopeAddr(slot)
                emitStoreToAddr(value.slot, addrSlot, value.type)
                if (localTarget.captureOwnerScopeId == null) {
                    localSlotIndexByName[localTarget.name]?.let { mirror ->
                        val mirrorSlot = scopeSlotCount + mirror
                        emitMove(value, mirrorSlot)
                        updateSlotType(mirrorSlot, value.type)
                    }
                }
            } else if (slot < scopeSlotCount) {
                val addrSlot = ensureScopeAddr(slot)
                emitStoreToAddr(value.slot, addrSlot, SlotType.OBJ)
                if (localTarget.captureOwnerScopeId == null) {
                    localSlotIndexByName[localTarget.name]?.let { mirror ->
                        val mirrorSlot = scopeSlotCount + mirror
                        emitMove(value, mirrorSlot)
                        updateSlotType(mirrorSlot, value.type)
                    }
                }
            } else {
                when (value.type) {
                    SlotType.INT -> builder.emit(Opcode.MOVE_INT, value.slot, slot)
                    SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, value.slot, slot)
                    SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, value.slot, slot)
                    else -> builder.emit(Opcode.MOVE_OBJ, value.slot, slot)
                }
            }
            updateSlotType(slot, value.type)
            propagateObjClass(value.type, value.slot, slot)
            updateNameObjClassFromSlot(localTarget.name, slot)
            return value
        }
        val nameTarget = when (val targetRef = ref.target) {
            is LocalVarRef -> targetRef.name
            is FastLocalVarRef -> targetRef.name
            else -> null
        }
        if (nameTarget != null) {
            val value = compileRef(assignValue(ref)) ?: return null
            val resolved = resolveAssignableSlotByName(nameTarget) ?: return null
            val slot = resolved.first
            val isMutable = resolved.second
            if (!isMutable) {
                val msgId = builder.addConst(BytecodeConst.StringVal("can't reassign val $nameTarget"))
                val msgSlot = allocSlot()
                builder.emit(Opcode.CONST_OBJ, msgId, msgSlot)
                val pos = (ref.target as? LocalVarRef)?.pos() ?: Pos.builtIn
                val posId = builder.addConst(BytecodeConst.PosVal(pos))
                builder.emit(Opcode.THROW, posId, msgSlot)
                return value
            }
            if (slot < scopeSlotCount && value.type != SlotType.UNKNOWN) {
                val addrSlot = ensureScopeAddr(slot)
                emitStoreToAddr(value.slot, addrSlot, value.type)
                localSlotIndexByName[nameTarget]?.let { mirror ->
                    val mirrorSlot = scopeSlotCount + mirror
                    emitMove(value, mirrorSlot)
                    updateSlotType(mirrorSlot, value.type)
                }
            } else if (slot < scopeSlotCount) {
                val addrSlot = ensureScopeAddr(slot)
                emitStoreToAddr(value.slot, addrSlot, SlotType.OBJ)
                localSlotIndexByName[nameTarget]?.let { mirror ->
                    val mirrorSlot = scopeSlotCount + mirror
                    emitMove(value, mirrorSlot)
                    updateSlotType(mirrorSlot, value.type)
                }
            } else {
                when (value.type) {
                    SlotType.INT -> builder.emit(Opcode.MOVE_INT, value.slot, slot)
                    SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, value.slot, slot)
                    SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, value.slot, slot)
                    else -> builder.emit(Opcode.MOVE_OBJ, value.slot, slot)
                }
            }
            updateSlotType(slot, value.type)
            propagateObjClass(value.type, value.slot, slot)
            updateNameObjClassFromSlot(nameTarget, slot)
            return value
        }
        val value = compileRef(assignValue(ref)) ?: return null
        val target = ref.target
        if (target is ClassScopeMemberRef) {
            val className = target.ownerClassName()
            val classSlot = compileRef(LocalVarRef(className, Pos.builtIn)) ?: run {
                val cls = resolveTypeNameClass(className) ?: return null
                val id = builder.addConst(BytecodeConst.ObjRef(cls))
                val slot = allocSlot()
                builder.emit(Opcode.CONST_OBJ, id, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            val classObj = ensureObjSlot(classSlot)
            val nameId = builder.addConst(BytecodeConst.StringVal(target.name))
            builder.emit(Opcode.SET_CLASS_SCOPE, classObj.slot, nameId, value.slot)
            return value
        }
        if (target is FieldRef) {
            val receiverClass = resolveReceiverClass(target.target)
                ?: throw BytecodeCompileException(
                    "Member assignment requires compile-time receiver type: ${target.name}",
                    Pos.builtIn
                )
            val receiver = compileRefWithFallback(target.target, null, Pos.builtIn) ?: return null
            if (receiverClass == ObjDynamic.type) {
                val nameId = builder.addConst(BytecodeConst.StringVal(target.name))
                if (!target.isOptional) {
                    builder.emit(Opcode.SET_DYNAMIC_MEMBER, receiver.slot, nameId, value.slot)
                } else {
                    val nullSlot = allocSlot()
                    builder.emit(Opcode.CONST_NULL, nullSlot)
                    val cmpSlot = allocSlot()
                    builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                    val endLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                    )
                    builder.emit(Opcode.SET_DYNAMIC_MEMBER, receiver.slot, nameId, value.slot)
                    builder.mark(endLabel)
                }
                return value
            }
            val fieldId = receiverClass.instanceFieldIdMap()[target.name]
            val methodId = if (fieldId == null) {
                receiverClass.instanceMethodIdMap(includeAbstract = true)[target.name]
            } else {
                null
            }
            if (fieldId == null && methodId == null) {
                val extSlot = resolveExtensionSetterSlot(receiverClass, target.name)
                    ?: throw BytecodeCompileException(
                        "Unknown member ${target.name} on ${receiverClass.className}",
                        Pos.builtIn
                    )
                val callee = ensureObjSlot(extSlot)
                val receiverObj = ensureObjSlot(receiver)
                val valueObj = ensureObjSlot(value)
                val argSlots = intArrayOf(allocSlot(), allocSlot())
                builder.emit(Opcode.MOVE_OBJ, receiverObj.slot, argSlots[0])
                builder.emit(Opcode.MOVE_OBJ, valueObj.slot, argSlots[1])
                updateSlotType(argSlots[0], SlotType.OBJ)
                updateSlotType(argSlots[1], SlotType.OBJ)
                val callArgs = CallArgs(base = argSlots[0], count = argSlots.size, planId = null)
                val encodedCount = encodeCallArgCount(callArgs) ?: return null
                val callDst = allocSlot()
                if (!target.isOptional) {
                    builder.emit(Opcode.CALL_SLOT, callee.slot, callArgs.base, encodedCount, callDst)
                } else {
                    val nullSlot = allocSlot()
                    builder.emit(Opcode.CONST_NULL, nullSlot)
                    val cmpSlot = allocSlot()
                    builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                    val endLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                    )
                    builder.emit(Opcode.CALL_SLOT, callee.slot, callArgs.base, encodedCount, callDst)
                    builder.mark(endLabel)
                }
                return value
            }
            val encodedFieldId = encodeMemberId(receiverClass, fieldId) ?: -1
            val encodedMethodId = encodeMemberId(receiverClass, methodId) ?: -1
            if (!target.isOptional) {
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, encodedFieldId, encodedMethodId, value.slot)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                )
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, encodedFieldId, encodedMethodId, value.slot)
                builder.mark(endLabel)
            }
            return value
        }
        if (target is ImplicitThisMemberRef) {
            val receiver = target.preferredThisTypeName()?.let { typeName ->
                compileThisVariantRef(typeName) ?: return null
            } ?: compileThisRef()
            val fieldId = target.fieldId ?: -1
            val methodId = target.methodId ?: -1
            if (fieldId < 0 && methodId < 0) {
                val typeName = target.preferredThisTypeName()
                    ?: throw BytecodeCompileException("Missing member id for ${target.name}", Pos.builtIn)
                val wrapperName = extensionPropertySetterName(typeName, target.name)
                val callee = resolveDirectNameSlot(wrapperName) ?: throw BytecodeCompileException(
                    "Missing extension wrapper for ${typeName}.${target.name}",
                    Pos.builtIn
                )
                val calleeObj = ensureObjSlot(callee)
                val receiverObj = ensureObjSlot(receiver)
                val valueObj = ensureObjSlot(value)
                val argSlots = intArrayOf(allocSlot(), allocSlot())
                builder.emit(Opcode.MOVE_OBJ, receiverObj.slot, argSlots[0])
                builder.emit(Opcode.MOVE_OBJ, valueObj.slot, argSlots[1])
                updateSlotType(argSlots[0], SlotType.OBJ)
                updateSlotType(argSlots[1], SlotType.OBJ)
                val callArgs = CallArgs(base = argSlots[0], count = argSlots.size, planId = null)
                val encodedCount = encodeCallArgCount(callArgs) ?: return null
                val callDst = allocSlot()
                builder.emit(Opcode.CALL_SLOT, calleeObj.slot, callArgs.base, encodedCount, callDst)
                return value
            }
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, value.slot)
            return value
        }
        if (target is ThisFieldSlotRef) {
            val receiver = compileThisRef()
            val fieldId = target.fieldId() ?: -1
            val methodId = target.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${target.name}", Pos.builtIn)
            }
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, value.slot)
            return value
        }
        if (target is QualifiedThisFieldSlotRef) {
            val receiver = compileThisVariantRef(target.receiverTypeName()) ?: return null
            val fieldId = target.fieldId() ?: -1
            val methodId = target.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${target.name}", Pos.builtIn)
            }
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, value.slot)
            return value
        }
        if (target is IndexRef) {
            val receiver = compileRefWithFallback(target.targetRef, null, Pos.builtIn) ?: return null
            if (!target.optionalRef) {
                val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, value.slot)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                )
                val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, value.slot)
                builder.mark(endLabel)
            }
            return value
        }
        return null
    }

    private fun compileAssignOp(ref: AssignOpRef): CompiledValue? {
        val localTarget = ref.target as? LocalSlotRef
        if (localTarget != null) {
            if (!allowLocalSlots) return compileEvalRef(ref)
            if (localTarget.isDelegated) return compileEvalRef(ref)
            val slot = resolveSlot(localTarget) ?: return null
            val targetType = slotTypes[slot] ?: SlotType.OBJ
            if (!localTarget.isMutable) {
                if (targetType != SlotType.OBJ && targetType != SlotType.UNKNOWN) return compileEvalRef(ref)
                val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
                val rhsObj = ensureObjSlot(rhs)
                val nameId = builder.addConst(BytecodeConst.StringVal(localTarget.name))
                if (nameId > 0xFFFF) return compileEvalRef(ref)
                val dst = allocSlot()
                builder.emit(Opcode.ASSIGN_OP_OBJ, ref.op.ordinal, slot, rhsObj.slot, dst, nameId)
                updateSlotType(dst, SlotType.OBJ)
                return CompiledValue(dst, SlotType.OBJ)
            }
            var rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            if (targetType == SlotType.OBJ && rhs.type != SlotType.OBJ) {
                rhs = ensureObjSlot(rhs)
            }
            if (slot < scopeSlotCount) {
                val addrSlot = ensureScopeAddr(slot)
                val current = allocSlot()
                emitLoadFromAddr(addrSlot, current, targetType)
                val result = when (ref.op) {
                    BinOp.PLUS -> compileAssignOpBinary(targetType, rhs, current, Opcode.ADD_INT, Opcode.ADD_REAL, Opcode.ADD_OBJ)
                    BinOp.MINUS -> compileAssignOpBinary(targetType, rhs, current, Opcode.SUB_INT, Opcode.SUB_REAL, Opcode.SUB_OBJ)
                    BinOp.STAR -> compileAssignOpBinary(targetType, rhs, current, Opcode.MUL_INT, Opcode.MUL_REAL, Opcode.MUL_OBJ)
                    BinOp.SLASH -> compileAssignOpBinary(targetType, rhs, current, Opcode.DIV_INT, Opcode.DIV_REAL, Opcode.DIV_OBJ)
                    BinOp.PERCENT -> compileAssignOpBinary(targetType, rhs, current, Opcode.MOD_INT, null, Opcode.MOD_OBJ)
                    else -> null
                } ?: return null
                emitStoreToAddr(current, addrSlot, result.type)
                updateSlotType(slot, result.type)
                return CompiledValue(current, result.type)
            }
            val out = slot
            val result = when (ref.op) {
                BinOp.PLUS -> compileAssignOpBinary(targetType, rhs, out, Opcode.ADD_INT, Opcode.ADD_REAL, Opcode.ADD_OBJ)
                BinOp.MINUS -> compileAssignOpBinary(targetType, rhs, out, Opcode.SUB_INT, Opcode.SUB_REAL, Opcode.SUB_OBJ)
                BinOp.STAR -> compileAssignOpBinary(targetType, rhs, out, Opcode.MUL_INT, Opcode.MUL_REAL, Opcode.MUL_OBJ)
                BinOp.SLASH -> compileAssignOpBinary(targetType, rhs, out, Opcode.DIV_INT, Opcode.DIV_REAL, Opcode.DIV_OBJ)
                BinOp.PERCENT -> compileAssignOpBinary(targetType, rhs, out, Opcode.MOD_INT, null, Opcode.MOD_OBJ)
                else -> null
            } ?: return null
            updateSlotType(out, result.type)
            return CompiledValue(out, result.type)
        }
        val varTarget = ref.target as? LocalVarRef
        if (varTarget != null) {
            return compileEvalRef(ref)
        }
        val objOp = when (ref.op) {
            BinOp.PLUS -> Opcode.ADD_OBJ
            BinOp.MINUS -> Opcode.SUB_OBJ
            BinOp.STAR -> Opcode.MUL_OBJ
            BinOp.SLASH -> Opcode.DIV_OBJ
            BinOp.PERCENT -> Opcode.MOD_OBJ
            else -> null
        } ?: return compileEvalRef(ref)
        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null) {
            throw BytecodeCompileException(
                "Member assignment requires compile-time receiver type: ${fieldTarget.name}",
                Pos.builtIn
            )
        }
        val implicitTarget = ref.target as? ImplicitThisMemberRef
        if (implicitTarget != null) {
            val receiver = compileThisRef()
            val fieldId = implicitTarget.fieldId ?: -1
            val methodId = implicitTarget.methodId ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${implicitTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        val thisFieldTarget = ref.target as? ThisFieldSlotRef
        if (thisFieldTarget != null) {
            val receiver = compileThisRef()
            val fieldId = thisFieldTarget.fieldId() ?: -1
            val methodId = thisFieldTarget.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${thisFieldTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        val qualifiedTarget = ref.target as? QualifiedThisFieldSlotRef
        if (qualifiedTarget != null) {
            val receiver = compileThisVariantRef(qualifiedTarget.receiverTypeName()) ?: return null
            val fieldId = qualifiedTarget.fieldId() ?: -1
            val methodId = qualifiedTarget.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${qualifiedTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        val indexTarget = ref.target as? IndexRef
        if (indexTarget != null) {
            val receiver = compileRefWithFallback(indexTarget.targetRef, null, Pos.builtIn) ?: return null
            val current = allocSlot()
            val result = allocSlot()
            val rhs = compileRef(ref.value) ?: return compileEvalRef(ref)
            if (!indexTarget.optionalRef) {
                val index = compileRefWithFallback(indexTarget.indexRef, null, Pos.builtIn) ?: return null
                builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, current)
                builder.emit(objOp, current, rhs.slot, result)
                builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
                updateSlotType(result, SlotType.OBJ)
                return CompiledValue(result, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val index = compileRefWithFallback(indexTarget.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, current)
            builder.emit(objOp, current, rhs.slot, result)
            builder.mark(endLabel)
            updateSlotType(result, SlotType.OBJ)
            return CompiledValue(result, SlotType.OBJ)
        }
        return compileEvalRef(ref)
    }

    private fun compileAssignIfNull(ref: AssignIfNullRef): CompiledValue? {
        val target = ref.target
        val currentValue = compileRefWithFallback(target, null, Pos.builtIn) ?: return null
        val currentObj = ensureObjSlot(currentValue)
        val resultSlot = allocSlot()
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, currentObj.slot, nullSlot, cmpSlot)
        val assignLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(assignLabel))
        )
        builder.emit(Opcode.MOVE_OBJ, currentObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(assignLabel)

        val newValue = compileRefWithFallback(ref.value, null, Pos.builtIn) ?: return null
        when (target) {
            is LocalSlotRef -> {
                if (!allowLocalSlots || !target.isMutable || target.isDelegated) return null
                val slot = resolveSlot(target) ?: return null
                if (slot < scopeSlotCount) {
                    val addrSlot = ensureScopeAddr(slot)
                    val storeType = if (newValue.type == SlotType.UNKNOWN) SlotType.OBJ else newValue.type
                    emitStoreToAddr(newValue.slot, addrSlot, storeType)
                } else {
                    when (newValue.type) {
                        SlotType.INT -> builder.emit(Opcode.MOVE_INT, newValue.slot, slot)
                        SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, newValue.slot, slot)
                        SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, newValue.slot, slot)
                        else -> builder.emit(Opcode.MOVE_OBJ, newValue.slot, slot)
                    }
                }
                updateSlotType(slot, newValue.type)
            }
            is FieldRef -> {
                val receiverClass = resolveReceiverClass(target.target)
                    ?: throw BytecodeCompileException(
                        "Member assignment requires compile-time receiver type: ${target.name}",
                        Pos.builtIn
                    )
                val receiver = compileRefWithFallback(target.target, null, Pos.builtIn) ?: return null
                val fieldId = receiverClass.instanceFieldIdMap()[target.name]
                val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[target.name]
                if (fieldId != null || methodId != null) {
                    if (!target.isOptional) {
                        builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId ?: -1, methodId ?: -1, newValue.slot)
                    } else {
                        val recvNull = allocSlot()
                        builder.emit(Opcode.CONST_NULL, recvNull)
                        val recvCmp = allocSlot()
                        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, recvNull, recvCmp)
                        val skipLabel = builder.label()
                        builder.emit(
                            Opcode.JMP_IF_TRUE,
                            listOf(CmdBuilder.Operand.IntVal(recvCmp), CmdBuilder.Operand.LabelRef(skipLabel))
                        )
                        builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId ?: -1, methodId ?: -1, newValue.slot)
                        builder.mark(skipLabel)
                    }
                } else {
                    val extSlot = resolveExtensionSetterSlot(receiverClass, target.name)
                        ?: throw BytecodeCompileException(
                            "Unknown member ${target.name} on ${receiverClass.className}",
                            Pos.builtIn
                        )
                    val callee = ensureObjSlot(extSlot)
                    val receiverObj = ensureObjSlot(receiver)
                    val valueObj = ensureObjSlot(newValue)
                    val argSlots = intArrayOf(allocSlot(), allocSlot())
                    builder.emit(Opcode.MOVE_OBJ, receiverObj.slot, argSlots[0])
                    builder.emit(Opcode.MOVE_OBJ, valueObj.slot, argSlots[1])
                    updateSlotType(argSlots[0], SlotType.OBJ)
                    updateSlotType(argSlots[1], SlotType.OBJ)
                    val callArgs = CallArgs(base = argSlots[0], count = argSlots.size, planId = null)
                    val encodedCount = encodeCallArgCount(callArgs) ?: return null
                    if (!target.isOptional) {
                        builder.emit(Opcode.CALL_SLOT, callee.slot, callArgs.base, encodedCount, resultSlot)
                    } else {
                        val recvNull = allocSlot()
                        builder.emit(Opcode.CONST_NULL, recvNull)
                        val recvCmp = allocSlot()
                        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, recvNull, recvCmp)
                        val skipLabel = builder.label()
                        builder.emit(
                            Opcode.JMP_IF_TRUE,
                            listOf(CmdBuilder.Operand.IntVal(recvCmp), CmdBuilder.Operand.LabelRef(skipLabel))
                        )
                        builder.emit(Opcode.CALL_SLOT, callee.slot, callArgs.base, encodedCount, resultSlot)
                        builder.mark(skipLabel)
                    }
                }
            }
            is IndexRef -> {
                val receiver = compileRefWithFallback(target.targetRef, null, Pos.builtIn) ?: return null
                if (!target.optionalRef) {
                    val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                    builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, newValue.slot)
                } else {
                    val recvNull = allocSlot()
                    builder.emit(Opcode.CONST_NULL, recvNull)
                    val recvCmp = allocSlot()
                    builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, recvNull, recvCmp)
                    val skipLabel = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(recvCmp), CmdBuilder.Operand.LabelRef(skipLabel))
                    )
                    val index = compileRefWithFallback(target.indexRef, null, Pos.builtIn) ?: return null
                    builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, newValue.slot)
                    builder.mark(skipLabel)
                }
            }
            else -> return null
        }
        val newObj = ensureObjSlot(newValue)
        builder.emit(Opcode.MOVE_OBJ, newObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileFieldRef(ref: FieldRef): CompiledValue? {
        val receiverClass = resolveReceiverClass(ref.target)
            ?: if (isAllowedObjectMember(ref.name)) {
                Obj.rootObjectType
            } else {
                throw BytecodeCompileException(
                    "Member access requires compile-time receiver type: ${ref.name}",
                    Pos.builtIn
                )
            }
        if (receiverClass == ObjDynamic.type) {
            val receiver = compileRefWithFallback(ref.target, null, Pos.builtIn) ?: return null
            val dst = allocSlot()
            val nameId = builder.addConst(BytecodeConst.StringVal(ref.name))
            if (!ref.isOptional) {
                builder.emit(Opcode.GET_DYNAMIC_MEMBER, receiver.slot, nameId, dst)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val nullLabel = builder.label()
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
                )
                builder.emit(Opcode.GET_DYNAMIC_MEMBER, receiver.slot, nameId, dst)
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
                builder.mark(nullLabel)
                builder.emit(Opcode.CONST_NULL, dst)
                builder.mark(endLabel)
            }
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val fieldId = receiverClass.instanceFieldIdMap()[ref.name]
        val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[ref.name]
        val encodedFieldId = encodeMemberId(receiverClass, fieldId)
        val encodedMethodId = encodeMemberId(receiverClass, methodId)
        val receiver = compileRefWithFallback(ref.target, null, Pos.builtIn) ?: return null
        val dst = allocSlot()
        if (fieldId != null || methodId != null) {
            if (!ref.isOptional) {
                builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, encodedFieldId ?: -1, encodedMethodId ?: -1, dst)
            } else {
                val nullSlot = allocSlot()
                builder.emit(Opcode.CONST_NULL, nullSlot)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
                val nullLabel = builder.label()
                val endLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
                )
                builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, encodedFieldId ?: -1, encodedMethodId ?: -1, dst)
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
                builder.mark(nullLabel)
                builder.emit(Opcode.CONST_NULL, dst)
                builder.mark(endLabel)
            }
            updateSlotType(dst, SlotType.OBJ)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val extSlot = resolveExtensionGetterSlot(receiverClass, ref.name)
            ?: throw BytecodeCompileException(
                "Unknown member ${ref.name} on ${receiverClass.className}",
                Pos.builtIn
            )
        val callee = ensureObjSlot(extSlot)
        if (!ref.isOptional) {
            val args = compileCallArgsWithReceiver(receiver, emptyList(), false) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val args = compileCallArgsWithReceiver(receiver, emptyList(), false) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileClassScopeMemberRef(ref: ClassScopeMemberRef): CompiledValue? {
        val className = ref.ownerClassName()
        val classSlot = compileRef(LocalVarRef(className, Pos.builtIn)) ?: run {
            val cls = resolveTypeNameClass(className) ?: return null
            val id = builder.addConst(BytecodeConst.ObjRef(cls))
            val slot = allocSlot()
            builder.emit(Opcode.CONST_OBJ, id, slot)
            updateSlotType(slot, SlotType.OBJ)
            CompiledValue(slot, SlotType.OBJ)
        }
        val classObj = ensureObjSlot(classSlot)
        val nameId = builder.addConst(BytecodeConst.StringVal(ref.name))
        val dst = allocSlot()
        builder.emit(Opcode.GET_CLASS_SCOPE, classObj.slot, nameId, dst)
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileThisFieldSlotRef(ref: ThisFieldSlotRef): CompiledValue? {
        val receiver = compileThisRef()
        val fieldId = ref.fieldId() ?: -1
        val methodId = ref.methodId() ?: -1
        if (fieldId < 0 && methodId < 0) {
            throw BytecodeCompileException("Missing member id for ${ref.name}", Pos.builtIn)
        }
        val dst = allocSlot()
        if (!ref.optional()) {
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileQualifiedThisFieldSlotRef(ref: QualifiedThisFieldSlotRef): CompiledValue? {
        val receiver = compileThisVariantRef(ref.receiverTypeName()) ?: return null
        val fieldId = ref.fieldId() ?: -1
        val methodId = ref.methodId() ?: -1
        if (fieldId < 0 && methodId < 0) {
            throw BytecodeCompileException("Missing member id for ${ref.name}", Pos.builtIn)
        }
        val dst = allocSlot()
        if (!ref.optional()) {
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileIndexRef(ref: IndexRef): CompiledValue? {
        val receiver = compileRefWithFallback(ref.targetRef, null, Pos.builtIn) ?: return null
        val dst = allocSlot()
        if (!ref.optionalRef) {
            val index = compileRefWithFallback(ref.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, dst)
        } else {
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val index = compileRefWithFallback(ref.indexRef, null, Pos.builtIn) ?: return null
            builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
        }
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileRangeRef(ref: RangeRef): CompiledValue? {
        val startSlot = if (ref.left != null) {
            val start = compileRefWithFallback(ref.left, null, Pos.builtIn) ?: return null
            ensureObjSlot(start).slot
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.CONST_NULL, slot)
            updateSlotType(slot, SlotType.OBJ)
            slot
        }
        val endSlot = if (ref.right != null) {
            val end = compileRefWithFallback(ref.right, null, Pos.builtIn) ?: return null
            ensureObjSlot(end).slot
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.CONST_NULL, slot)
            updateSlotType(slot, SlotType.OBJ)
            slot
        }
        val inclusiveSlot = allocSlot()
        val inclusiveId = builder.addConst(BytecodeConst.Bool(ref.isEndInclusive))
        builder.emit(Opcode.CONST_BOOL, inclusiveId, inclusiveSlot)
        val stepSlot = if (ref.step != null) {
            val step = compileRefWithFallback(ref.step, null, Pos.builtIn) ?: return null
            ensureObjSlot(step).slot
        } else {
            val slot = allocSlot()
            builder.emit(Opcode.CONST_NULL, slot)
            updateSlotType(slot, SlotType.OBJ)
            slot
        }
        val dst = allocSlot()
        builder.emit(Opcode.MAKE_RANGE, startSlot, endSlot, inclusiveSlot, stepSlot, dst)
        updateSlotType(dst, SlotType.OBJ)
        slotObjClass[dst] = ObjRange.type
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileAssignOpBinary(
        targetType: SlotType,
        rhs: CompiledValue,
        out: Int,
        intOp: Opcode,
        realOp: Opcode?,
        objOp: Opcode?,
    ): CompiledValue? {
        return when (targetType) {
            SlotType.INT -> {
                when (rhs.type) {
                    SlotType.INT -> {
                        builder.emit(intOp, out, rhs.slot, out)
                        CompiledValue(out, SlotType.INT)
                    }
                    SlotType.REAL -> {
                        if (realOp == null) return null
                        val left = allocSlot()
                        builder.emit(Opcode.INT_TO_REAL, out, left)
                        builder.emit(realOp, left, rhs.slot, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    SlotType.OBJ -> {
                        if (objOp == null) return null
                        val leftObj = allocSlot()
                        builder.emit(Opcode.BOX_OBJ, out, leftObj)
                        builder.emit(objOp, leftObj, rhs.slot, out)
                        CompiledValue(out, SlotType.OBJ)
                    }
                    else -> null
                }
            }
            SlotType.REAL -> {
                if (realOp == null) return null
                when (rhs.type) {
                    SlotType.REAL -> {
                        builder.emit(realOp, out, rhs.slot, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    SlotType.INT -> {
                        val right = allocSlot()
                        builder.emit(Opcode.INT_TO_REAL, rhs.slot, right)
                        builder.emit(realOp, out, right, out)
                        CompiledValue(out, SlotType.REAL)
                    }
                    SlotType.OBJ -> {
                        if (objOp == null) return null
                        val leftObj = allocSlot()
                        builder.emit(Opcode.BOX_OBJ, out, leftObj)
                        builder.emit(objOp, leftObj, rhs.slot, out)
                        CompiledValue(out, SlotType.OBJ)
                    }
                    else -> null
                }
            }
            SlotType.OBJ -> {
                if (objOp == null) return null
                if (rhs.type != SlotType.OBJ) return null
                builder.emit(objOp, out, rhs.slot, out)
                CompiledValue(out, SlotType.OBJ)
            }
            else -> null
        }
    }

    private fun compileIncDec(ref: IncDecRef, wantResult: Boolean): CompiledValue? {
        val target = ref.target as? LocalSlotRef
        if (target != null) {
            if (!allowLocalSlots) return null
            if (!target.isMutable || target.isDelegated) return null
            val slot = resolveSlot(target) ?: return null
            val slotType = slotTypes[slot] ?: SlotType.UNKNOWN
            if (slot < scopeSlotCount && slotType != SlotType.UNKNOWN) {
                val addrSlot = ensureScopeAddr(slot)
                val current = allocSlot()
                emitLoadFromAddr(addrSlot, current, slotType)
                val result = when (slotType) {
                SlotType.INT -> {
                    if (wantResult && ref.isPost) {
                        val old = allocSlot()
                        builder.emit(Opcode.MOVE_INT, current, old)
                        builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, current)
                        emitStoreToAddr(current, addrSlot, SlotType.INT)
                        CompiledValue(old, SlotType.INT)
                    } else {
                        builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, current)
                        emitStoreToAddr(current, addrSlot, SlotType.INT)
                        CompiledValue(current, SlotType.INT)
                    }
                }
                SlotType.REAL -> {
                    val oneSlot = allocSlot()
                    val oneId = builder.addConst(BytecodeConst.RealVal(1.0))
                    builder.emit(Opcode.CONST_REAL, oneId, oneSlot)
                    if (wantResult && ref.isPost) {
                        val old = allocSlot()
                        builder.emit(Opcode.MOVE_REAL, current, old)
                        val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                        builder.emit(op, current, oneSlot, current)
                        emitStoreToAddr(current, addrSlot, SlotType.REAL)
                        CompiledValue(old, SlotType.REAL)
                    } else {
                        val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                        builder.emit(op, current, oneSlot, current)
                        emitStoreToAddr(current, addrSlot, SlotType.REAL)
                        CompiledValue(current, SlotType.REAL)
                    }
                }
                SlotType.OBJ -> {
                    val oneSlot = allocSlot()
                    val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
                    builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
                    val boxed = allocSlot()
                    builder.emit(Opcode.BOX_OBJ, current, boxed)
                    if (wantResult && ref.isPost) {
                        val result = allocSlot()
                        val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                        builder.emit(op, boxed, oneSlot, result)
                        builder.emit(Opcode.MOVE_OBJ, result, boxed)
                        emitStoreToAddr(boxed, addrSlot, SlotType.OBJ)
                        updateSlotType(slot, SlotType.OBJ)
                        CompiledValue(boxed, SlotType.OBJ)
                    } else {
                        val result = allocSlot()
                        val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                        builder.emit(op, boxed, oneSlot, result)
                        builder.emit(Opcode.MOVE_OBJ, result, boxed)
                        emitStoreToAddr(boxed, addrSlot, SlotType.OBJ)
                        updateSlotType(slot, SlotType.OBJ)
                        CompiledValue(result, SlotType.OBJ)
                    }
                }
                else -> null
            }
                if (result != null) return result
            }
            return when (slotType) {
            SlotType.INT -> {
                if (wantResult && ref.isPost) {
                    val old = allocSlot()
                    builder.emit(Opcode.MOVE_INT, slot, old)
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    CompiledValue(old, SlotType.INT)
                } else {
                    builder.emit(if (ref.isIncrement) Opcode.INC_INT else Opcode.DEC_INT, slot)
                    CompiledValue(slot, SlotType.INT)
                }
            }
            SlotType.REAL -> {
                val oneSlot = allocSlot()
                val oneId = builder.addConst(BytecodeConst.RealVal(1.0))
                builder.emit(Opcode.CONST_REAL, oneId, oneSlot)
                if (wantResult && ref.isPost) {
                    val old = allocSlot()
                    builder.emit(Opcode.MOVE_REAL, slot, old)
                    val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                    builder.emit(op, slot, oneSlot, slot)
                    CompiledValue(old, SlotType.REAL)
                } else {
                    val op = if (ref.isIncrement) Opcode.ADD_REAL else Opcode.SUB_REAL
                    builder.emit(op, slot, oneSlot, slot)
                    CompiledValue(slot, SlotType.REAL)
                }
            }
            SlotType.OBJ -> {
                val oneSlot = allocSlot()
                val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
                builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
                val current = allocSlot()
                builder.emit(Opcode.BOX_OBJ, slot, current)
                if (wantResult && ref.isPost) {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(current, SlotType.OBJ)
                } else {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(result, SlotType.OBJ)
                }
            }
            SlotType.UNKNOWN -> {
                val oneSlot = allocSlot()
                val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
                builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
                val current = allocSlot()
                builder.emit(Opcode.BOX_OBJ, slot, current)
                if (wantResult && ref.isPost) {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(current, SlotType.OBJ)
                } else {
                    val result = allocSlot()
                    val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
                    builder.emit(op, current, oneSlot, result)
                    builder.emit(Opcode.MOVE_OBJ, result, slot)
                    updateSlotType(slot, SlotType.OBJ)
                    CompiledValue(result, SlotType.OBJ)
                }
            }
                else -> null
            }
        }

        val thisFieldTarget = ref.target as? ThisFieldSlotRef
        if (thisFieldTarget != null) {
            val receiver = compileThisRef()
            val fieldId = thisFieldTarget.fieldId() ?: -1
            val methodId = thisFieldTarget.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${thisFieldTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            updateSlotType(current, SlotType.OBJ)
            val oneSlot = allocSlot()
            val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
            builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
            updateSlotType(oneSlot, SlotType.OBJ)
            val result = allocSlot()
            val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
            if (wantResult && ref.isPost) {
                val old = allocSlot()
                builder.emit(Opcode.MOVE_OBJ, current, old)
                builder.emit(op, current, oneSlot, result)
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
                return CompiledValue(old, SlotType.OBJ)
            }
            builder.emit(op, current, oneSlot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            return CompiledValue(result, SlotType.OBJ)
        }

        val implicitTarget = ref.target as? ImplicitThisMemberRef
        if (implicitTarget != null) {
            val receiver = compileThisRef()
            val fieldId = implicitTarget.fieldId ?: -1
            val methodId = implicitTarget.methodId ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${implicitTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            updateSlotType(current, SlotType.OBJ)
            val oneSlot = allocSlot()
            val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
            builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
            updateSlotType(oneSlot, SlotType.OBJ)
            val result = allocSlot()
            val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
            if (wantResult && ref.isPost) {
                val old = allocSlot()
                builder.emit(Opcode.MOVE_OBJ, current, old)
                builder.emit(op, current, oneSlot, result)
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
                return CompiledValue(old, SlotType.OBJ)
            }
            builder.emit(op, current, oneSlot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            return CompiledValue(result, SlotType.OBJ)
        }

        val qualifiedTarget = ref.target as? QualifiedThisFieldSlotRef
        if (qualifiedTarget != null) {
            val receiver = compileThisVariantRef(qualifiedTarget.receiverTypeName()) ?: return null
            val fieldId = qualifiedTarget.fieldId() ?: -1
            val methodId = qualifiedTarget.methodId() ?: -1
            if (fieldId < 0 && methodId < 0) {
                throw BytecodeCompileException("Missing member id for ${qualifiedTarget.name}", Pos.builtIn)
            }
            val current = allocSlot()
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId, methodId, current)
            updateSlotType(current, SlotType.OBJ)
            val oneSlot = allocSlot()
            val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
            builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
            updateSlotType(oneSlot, SlotType.OBJ)
            val result = allocSlot()
            val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
            if (wantResult && ref.isPost) {
                val old = allocSlot()
                builder.emit(Opcode.MOVE_OBJ, current, old)
                builder.emit(op, current, oneSlot, result)
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
                return CompiledValue(old, SlotType.OBJ)
            }
            builder.emit(op, current, oneSlot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId, methodId, result)
            return CompiledValue(result, SlotType.OBJ)
        }

        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null) {
            if (fieldTarget.isOptional) return null
            val receiverClass = resolveReceiverClass(fieldTarget.target)
                ?: throw BytecodeCompileException(
                    "Member access requires compile-time receiver type: ${fieldTarget.name}",
                    Pos.builtIn
                )
            val fieldId = receiverClass.instanceFieldIdMap()[fieldTarget.name]
            val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[fieldTarget.name]
            if (fieldId == null && methodId == null) return null
            val receiver = compileRefWithFallback(fieldTarget.target, null, Pos.builtIn) ?: return null
            val current = allocSlot()
            builder.emit(Opcode.GET_MEMBER_SLOT, receiver.slot, fieldId ?: -1, methodId ?: -1, current)
            updateSlotType(current, SlotType.OBJ)
            val oneSlot = allocSlot()
            val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
            builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
            updateSlotType(oneSlot, SlotType.OBJ)
            val result = allocSlot()
            val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
            if (wantResult && ref.isPost) {
                val old = allocSlot()
                builder.emit(Opcode.MOVE_OBJ, current, old)
                builder.emit(op, current, oneSlot, result)
                builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId ?: -1, methodId ?: -1, result)
                return CompiledValue(old, SlotType.OBJ)
            }
            builder.emit(op, current, oneSlot, result)
            builder.emit(Opcode.SET_MEMBER_SLOT, receiver.slot, fieldId ?: -1, methodId ?: -1, result)
            return CompiledValue(result, SlotType.OBJ)
        }

        val indexTarget = ref.target as? IndexRef ?: return null
        if (indexTarget.optionalRef) return null
        val receiver = compileRefWithFallback(indexTarget.targetRef, null, Pos.builtIn) ?: return null
        val index = compileRefWithFallback(indexTarget.indexRef, null, Pos.builtIn) ?: return null
        val current = allocSlot()
        builder.emit(Opcode.GET_INDEX, receiver.slot, index.slot, current)
        updateSlotType(current, SlotType.OBJ)
        val oneSlot = allocSlot()
        val oneId = builder.addConst(BytecodeConst.ObjRef(ObjInt.One))
        builder.emit(Opcode.CONST_OBJ, oneId, oneSlot)
        val result = allocSlot()
        val op = if (ref.isIncrement) Opcode.ADD_OBJ else Opcode.SUB_OBJ
        if (wantResult && ref.isPost) {
            val old = allocSlot()
            builder.emit(Opcode.MOVE_OBJ, current, old)
            builder.emit(op, current, oneSlot, result)
            builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
            return CompiledValue(old, SlotType.OBJ)
        }
        builder.emit(op, current, oneSlot, result)
        builder.emit(Opcode.SET_INDEX, receiver.slot, index.slot, result)
        return CompiledValue(result, SlotType.OBJ)
    }

    private fun compileConditional(ref: ConditionalRef): CompiledValue? {
        val condition = compileRefWithFallback(ref.condition, SlotType.BOOL, Pos.builtIn) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileRefWithFallback(ref.ifTrue, null, Pos.builtIn) ?: return null
        val thenObj = ensureObjSlot(thenValue)
        builder.emit(Opcode.MOVE_OBJ, thenObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        val elseValue = compileRefWithFallback(ref.ifFalse, null, Pos.builtIn) ?: return null
        val elseObj = ensureObjSlot(elseValue)
        builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileElvis(ref: ElvisRef): CompiledValue? {
        val leftValue = compileRefWithFallback(ref.left, null, Pos.builtIn) ?: return null
        val leftObj = ensureObjSlot(leftValue)
        val resultSlot = allocSlot()
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, leftObj.slot, nullSlot, cmpSlot)
        val rightLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(rightLabel))
        )
        builder.emit(Opcode.MOVE_OBJ, leftObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(rightLabel)
        val rightValue = compileRefWithFallback(ref.right, null, Pos.builtIn) ?: return null
        val rightObj = ensureObjSlot(rightValue)
        builder.emit(Opcode.MOVE_OBJ, rightObj.slot, resultSlot)
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileWhen(stmt: WhenStatement, wantResult: Boolean): CompiledValue? {
        val subjectRef = extractFlowTypeSubject(stmt.value)
        val subjectValue = compileStatementValueOrFallback(stmt.value) ?: return null
        val subjectObj = ensureObjSlot(subjectValue)
        val resultSlot = allocSlot()
        if (wantResult) {
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)
            updateSlotType(resultSlot, SlotType.OBJ)
        }
        val endLabel = builder.label()
        for (case in stmt.cases) {
            val caseLabel = builder.label()
            val nextCaseLabel = builder.label()
            for (cond in case.conditions) {
                val condValue = compileWhenCondition(cond, subjectObj) ?: return null
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(condValue.slot), CmdBuilder.Operand.LabelRef(caseLabel))
                )
            }
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(nextCaseLabel)))
            builder.mark(caseLabel)
            val caseOverride = flowTypeOverrideForWhenCase(subjectRef, case.conditions)
            val caseRestore = applyFlowTypeOverride(caseOverride)
            val bodyValue = compileStatementValueOrFallback(case.block, wantResult) ?: return null
            if (wantResult) {
                val bodyObj = ensureObjSlot(bodyValue)
                builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
            }
            restoreFlowTypeOverride(caseRestore)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nextCaseLabel)
        }
        stmt.elseCase?.let {
            val elseValue = compileStatementValueOrFallback(it, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
        }
        builder.mark(endLabel)
        return if (wantResult) {
            updateSlotType(resultSlot, SlotType.OBJ)
            CompiledValue(resultSlot, SlotType.OBJ)
        } else {
            subjectObj
        }
    }

    private fun compileWhenCondition(cond: WhenCondition, subjectObj: CompiledValue): CompiledValue? {
        val subject = ensureObjSlot(subjectObj)
        return when (cond) {
            is WhenEqualsCondition -> {
                val expected = compileStatementValueOrFallback(cond.expr) ?: return null
                val expectedObj = ensureObjSlot(expected)
                val dst = allocSlot()
                builder.emit(Opcode.CMP_EQ_OBJ, expectedObj.slot, subject.slot, dst)
                updateSlotType(dst, SlotType.BOOL)
                CompiledValue(dst, SlotType.BOOL)
            }
            is WhenInCondition -> {
                val container = compileStatementValueOrFallback(cond.expr) ?: return null
                val containerObj = ensureObjSlot(container)
                val baseDst = allocSlot()
                builder.emit(Opcode.CONTAINS_OBJ, containerObj.slot, subject.slot, baseDst)
                updateSlotType(baseDst, SlotType.BOOL)
                if (!cond.negated) {
                    CompiledValue(baseDst, SlotType.BOOL)
                } else {
                    val neg = allocSlot()
                    builder.emit(Opcode.NOT_BOOL, baseDst, neg)
                    updateSlotType(neg, SlotType.BOOL)
                    CompiledValue(neg, SlotType.BOOL)
                }
            }
            is WhenIsCondition -> {
                val typeValue = compileStatementValueOrFallback(cond.expr) ?: return null
                val typeObj = ensureObjSlot(typeValue)
                val baseDst = allocSlot()
                builder.emit(Opcode.CHECK_IS, subject.slot, typeObj.slot, baseDst)
                updateSlotType(baseDst, SlotType.BOOL)
                if (!cond.negated) {
                    CompiledValue(baseDst, SlotType.BOOL)
                } else {
                    val neg = allocSlot()
                    builder.emit(Opcode.NOT_BOOL, baseDst, neg)
                    updateSlotType(neg, SlotType.BOOL)
                    CompiledValue(neg, SlotType.BOOL)
                }
            }
        }
    }

    private fun ensureObjSlot(value: CompiledValue): CompiledValue {
        if (value.type == SlotType.OBJ) return value
        val dst = allocSlot()
        builder.emit(Opcode.BOX_OBJ, value.slot, dst)
        updateSlotType(dst, SlotType.OBJ)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileCall(ref: CallRef): CompiledValue? {
        val callPos = callSitePos()
        val localTarget = ref.target as? LocalVarRef
        if (localTarget != null) {
            val direct = resolveDirectNameSlot(localTarget.name)
            if (direct == null) {
                val thisSlot = resolveDirectNameSlot("this")
                if (thisSlot != null) {
                    throw BytecodeCompileException(
                        "Unresolved member call '${localTarget.name}': missing compile-time member id",
                        Pos.builtIn
                    )
                }
            }
        }
        val fieldTarget = ref.target as? FieldRef
        if (fieldTarget != null) {
            throw BytecodeCompileException(
                "Member call requires compile-time receiver type: ${fieldTarget.name}",
                Pos.builtIn
            )
        }
        val initClass = when (localTarget?.name) {
            "List" -> ObjList.type
            "Map" -> ObjMap.type
            else -> null
        }
        val callee = compileRefWithFallback(ref.target, null, refPosOrCurrent(ref.target)) ?: return null
        val dst = allocSlot()
        if (!ref.isOptionalInvoke) {
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
            if (initClass != null) {
                slotObjClass[dst] = initClass
            }
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, callee.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        setPos(callPos)
        builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
        if (initClass != null) {
            slotObjClass[dst] = initClass
        }
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun resolveDirectNameSlot(name: String): CompiledValue? {
        loopSlotOverrides[name]?.let { slot ->
            val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
            return CompiledValue(slot, resolved)
        }
        if (!allowLocalSlots) return null
        if (!forceScopeSlots) {
            scopeSlotIndexByName[name]?.let { slot ->
                val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                return CompiledValue(slot, resolved)
            }
            localSlotIndexByName[name]?.let { localIndex ->
                val slot = scopeSlotCount + localIndex
                val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
                return CompiledValue(slot, resolved)
            }
            return null
        }
        scopeSlotIndexByName[name]?.let { slot ->
            val resolved = slotTypes[slot] ?: SlotType.UNKNOWN
            return CompiledValue(slot, resolved)
        }
        return null
    }

    private fun resolveAssignableSlotByName(name: String): Pair<Int, Boolean>? {
        localSlotIndexByName[name]?.let { localIndex ->
            val slot = scopeSlotCount + localIndex
            val mutable = localSlotMutables.getOrNull(localIndex) ?: true
            return slot to mutable
        }
        scopeSlotIndexByName[name]?.let { slot ->
            val mutable = scopeSlotMutables.getOrNull(slot) ?: true
            return slot to mutable
        }
        return null
    }

    private fun compileMethodCall(ref: MethodCallRef): CompiledValue? {
        val callPos = callSitePos()
        val receiverClass = resolveReceiverClass(ref.receiver)
            ?: if (isAllowedObjectMember(ref.name)) {
                Obj.rootObjectType
            } else {
                throw BytecodeCompileException(
                    "Member call requires compile-time receiver type: ${ref.name}",
                    Pos.builtIn
                )
            }
        val receiver = compileRefWithFallback(ref.receiver, null, refPosOrCurrent(ref.receiver)) ?: return null
        val dst = allocSlot()
        if (receiverClass == ObjDynamic.type) {
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            val nameId = builder.addConst(BytecodeConst.StringVal(ref.name))
            if (!ref.isOptional) {
                setPos(callPos)
                builder.emit(Opcode.CALL_DYNAMIC_MEMBER, receiver.slot, nameId, args.base, encodedCount, dst)
                return CompiledValue(dst, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            setPos(callPos)
            builder.emit(Opcode.CALL_DYNAMIC_MEMBER, receiver.slot, nameId, args.base, encodedCount, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[ref.name]
        if (methodId != null) {
            val encodedMethodId = encodeMemberId(receiverClass, methodId) ?: methodId
            if (!ref.isOptional) {
                val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
                val encodedCount = encodeCallArgCount(args) ?: return null
                setPos(callPos)
                builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, encodedMethodId, args.base, encodedCount, dst)
                return CompiledValue(dst, SlotType.OBJ)
            }
            val nullSlot = allocSlot()
            builder.emit(Opcode.CONST_NULL, nullSlot)
            val cmpSlot = allocSlot()
            builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
            val nullLabel = builder.label()
            val endLabel = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
            )
            val args = compileCallArgs(ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, encodedMethodId, args.base, encodedCount, dst)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(nullLabel)
            builder.emit(Opcode.CONST_NULL, dst)
            builder.mark(endLabel)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val extSlot = resolveExtensionCallableSlot(receiverClass, ref.name)
            ?: throw BytecodeCompileException(
                "Unknown member ${ref.name} on ${receiverClass.className}",
                Pos.builtIn
            )
        val callee = ensureObjSlot(extSlot)
        if (!ref.isOptional) {
            val args = compileCallArgsWithReceiver(receiver, ref.args, ref.tailBlock) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgsWithReceiver(receiver, ref.args, ref.tailBlock) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        setPos(callPos)
        builder.emit(Opcode.CALL_SLOT, callee.slot, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun compileThisMethodSlotCall(ref: ThisMethodSlotCallRef): CompiledValue? {
        val callPos = callSitePos()
        val receiver = compileThisRef()
        val methodId = ref.methodId() ?: throw BytecodeCompileException(
            "Missing member id for ${ref.methodName()}",
            Pos.builtIn
        )
        val dst = allocSlot()
        if (!ref.optionalInvoke()) {
            val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        setPos(callPos)
        builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private fun receiverDebugInfo(ref: ObjRef): String {
        val kind = ref::class.simpleName ?: "ObjRef"
        return when (ref) {
            is LocalVarRef -> {
                val direct = resolveDirectNameSlot(ref.name)
                val slot = direct?.slot
                val slotCls = slot?.let { slotObjClass[it]?.className }
                val nameCls = nameObjClass[ref.name]?.className
                " receiver=$kind(${ref.name}) slot=$slot slotClass=$slotCls nameClass=$nameCls"
            }
            is LocalSlotRef -> {
                val slot = resolveSlot(ref)
                val slotCls = slot?.let { slotObjClass[it]?.className }
                val nameCls = nameObjClass[ref.name]?.className
                val scopeId = refScopeId(ref)
                val slotId = refSlot(ref)
                val initCls = slotInitClassByKey[ScopeSlotKey(scopeId, slotId)]?.className
                " receiver=$kind(${ref.name}) slot=$slot scopeId=$scopeId slotId=$slotId slotClass=$slotCls nameClass=$nameCls initClass=$initCls"
            }
            else -> " receiver=$kind"
        }
    }

    private fun compileQualifiedThisMethodSlotCall(ref: QualifiedThisMethodSlotCallRef): CompiledValue? {
        val callPos = callSitePos()
        val receiver = compileThisVariantRef(ref.receiverTypeName()) ?: return null
        val methodId = ref.methodId() ?: throw BytecodeCompileException(
            "Missing member id for ${ref.methodName()}",
            Pos.builtIn
        )
        val dst = allocSlot()
        if (!ref.optionalInvoke()) {
            val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
            val encodedCount = encodeCallArgCount(args) ?: return null
            setPos(callPos)
            builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
            return CompiledValue(dst, SlotType.OBJ)
        }
        val nullSlot = allocSlot()
        builder.emit(Opcode.CONST_NULL, nullSlot)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_REF_EQ_OBJ, receiver.slot, nullSlot, cmpSlot)
        val nullLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(nullLabel))
        )
        val args = compileCallArgs(ref.arguments(), ref.hasTailBlock()) ?: return null
        val encodedCount = encodeCallArgCount(args) ?: return null
        setPos(callPos)
        builder.emit(Opcode.CALL_MEMBER_SLOT, receiver.slot, methodId, args.base, encodedCount, dst)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(nullLabel)
        builder.emit(Opcode.CONST_NULL, dst)
        builder.mark(endLabel)
        return CompiledValue(dst, SlotType.OBJ)
    }

    private data class CallArgs(val base: Int, val count: Int, val planId: Int?)

    private fun resolveExtensionCallableSlot(receiverClass: ObjClass, memberName: String): CompiledValue? {
        for (cls in receiverClass.mro) {
            val candidate = extensionCallableName(cls.className, memberName)
            if (allowedScopeNames != null && !allowedScopeNames.contains(candidate)) continue
            resolveDirectNameSlot(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveExtensionGetterSlot(receiverClass: ObjClass, memberName: String): CompiledValue? {
        for (cls in receiverClass.mro) {
            val candidate = extensionPropertyGetterName(cls.className, memberName)
            if (allowedScopeNames != null && !allowedScopeNames.contains(candidate)) continue
            resolveDirectNameSlot(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveExtensionSetterSlot(receiverClass: ObjClass, memberName: String): CompiledValue? {
        for (cls in receiverClass.mro) {
            val candidate = extensionPropertySetterName(cls.className, memberName)
            if (allowedScopeNames != null && !allowedScopeNames.contains(candidate)) continue
            resolveDirectNameSlot(candidate)?.let { return it }
        }
        return null
    }

    private fun compileCallArgsWithReceiver(
        receiver: CompiledValue,
        args: List<ParsedArgument>,
        tailBlock: Boolean
    ): CallArgs? {
        val argSlots = IntArray(args.size + 1) { allocSlot() }
        val receiverObj = ensureObjSlot(receiver)
        builder.emit(Opcode.MOVE_OBJ, receiverObj.slot, argSlots[0])
        updateSlotType(argSlots[0], SlotType.OBJ)
        val needPlan = tailBlock || args.any { it.isSplat || it.name != null }
        val specs = if (needPlan) ArrayList<BytecodeConst.CallArgSpec>(args.size + 1) else null
        specs?.add(BytecodeConst.CallArgSpec(null, false))
        for ((index, arg) in args.withIndex()) {
            val compiled = compileArgValue(arg.value) ?: return null
            val dst = argSlots[index + 1]
            if (compiled.slot != dst || compiled.type != SlotType.OBJ) {
                builder.emit(Opcode.BOX_OBJ, compiled.slot, dst)
            }
            updateSlotType(dst, SlotType.OBJ)
            specs?.add(BytecodeConst.CallArgSpec(arg.name, arg.isSplat))
        }
        val planId = if (needPlan) {
            builder.addConst(BytecodeConst.CallArgsPlan(tailBlock, specs ?: emptyList()))
        } else {
            null
        }
        return CallArgs(base = argSlots[0], count = argSlots.size, planId = planId)
    }

    private fun compileCallArgs(args: List<ParsedArgument>, tailBlock: Boolean): CallArgs? {
        if (args.isEmpty()) return CallArgs(base = 0, count = 0, planId = null)
        val argSlots = IntArray(args.size) { allocSlot() }
        val needPlan = tailBlock || args.any { it.isSplat || it.name != null }
        val specs = if (needPlan) ArrayList<BytecodeConst.CallArgSpec>(args.size) else null
        for ((index, arg) in args.withIndex()) {
            val compiled = compileArgValue(arg.value) ?: return null
            val dst = argSlots[index]
            if (compiled.slot != dst || compiled.type != SlotType.OBJ) {
                builder.emit(Opcode.BOX_OBJ, compiled.slot, dst)
            }
            updateSlotType(dst, SlotType.OBJ)
            specs?.add(BytecodeConst.CallArgSpec(arg.name, arg.isSplat))
        }
        val planId = if (needPlan) {
            builder.addConst(BytecodeConst.CallArgsPlan(tailBlock, specs ?: emptyList()))
        } else {
            null
        }
        return CallArgs(base = argSlots[0], count = argSlots.size, planId = planId)
    }

    private fun compileArgValue(stmt: Statement): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, null, stmt.pos)
            else -> {
                throw BytecodeCompileException(
                    "Bytecode compile error: unsupported argument expression",
                    stmt.pos
                )
            }
        }
    }

    private fun encodeCallArgCount(args: CallArgs): Int? {
        val planId = args.planId ?: return args.count
        if (planId > 0x7FFF) return null
        return 0x8000 or planId
    }

    private fun compileIf(name: String, stmt: IfStatement): CmdFunction? {
        val conditionTarget = if (stmt.condition is BytecodeStatement) {
            stmt.condition.original
        } else {
            stmt.condition
        }
        val conditionStmt = conditionTarget as? ExpressionStatement ?: return null
        val condValue = compileRefWithFallback(conditionStmt.ref, SlotType.BOOL, stmt.pos) ?: return null
        if (condValue.type != SlotType.BOOL) return null

        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()

        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condValue.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenValue = compileStatementValueOrFallback(stmt.ifBody) ?: return null
        emitMove(thenValue, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))

        builder.mark(elseLabel)
        if (stmt.elseBody != null) {
            val elseValue = compileStatementValueOrFallback(stmt.elseBody) ?: return null
            emitMove(elseValue, resultSlot)
        } else {
            val id = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, id, resultSlot)
        }

        builder.mark(endLabel)
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileForIn(name: String, stmt: net.sergeych.lyng.ForInStatement): CmdFunction? {
        val resultSlot = emitForIn(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileWhile(name: String, stmt: net.sergeych.lyng.WhileStatement): CmdFunction? {
        if (!allowLocalSlots) return null
        val resultSlot = emitWhile(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileDoWhile(name: String, stmt: net.sergeych.lyng.DoWhileStatement): CmdFunction? {
        if (!allowLocalSlots) return null
        val resultSlot = emitDoWhile(stmt, true) ?: return null
        builder.emit(Opcode.RET, resultSlot)
        val localCount = maxOf(nextSlot, resultSlot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileBlock(name: String, stmt: BlockStatement): CmdFunction? {
        val result = if (shouldInlineBlock(stmt)) {
            emitInlineStatements(stmt.statements(), true)
        } else {
            emitBlock(stmt, true)
        } ?: return null
        builder.emit(Opcode.RET, result.slot)
        val localCount = maxOf(nextSlot, result.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileVarDecl(name: String, stmt: VarDeclStatement): CmdFunction? {
        val result = emitVarDecl(stmt) ?: return null
        builder.emit(Opcode.RET, result.slot)
        val localCount = maxOf(nextSlot, result.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileStatementValue(stmt: Statement): CompiledValue? {
        return when (stmt) {
            is ExpressionStatement -> compileRefWithFallback(stmt.ref, null, stmt.pos)
            else -> null
        }
    }

    private fun emitFallbackStatement(stmt: Statement): CompiledValue {
        throw BytecodeCompileException(
            "Bytecode compile error: unsupported statement",
            stmt.pos
        )
    }

    private fun emitStatementEval(stmt: Statement): CompiledValue {
        val stmtName = stmt::class.simpleName ?: "UnknownStatement"
        throw BytecodeCompileException("Unsupported statement in bytecode: $stmtName", stmt.pos)
    }

    private fun compileStatementValueOrFallback(stmt: Statement, needResult: Boolean = true): CompiledValue? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        setPos(target.pos)
        return if (needResult) {
            when (target) {
                is ExpressionStatement -> compileRefWithFallback(target.ref, null, target.pos)
                is IfStatement -> compileIfExpression(target)
                is net.sergeych.lyng.ForInStatement -> {
                    val resultSlot = emitForIn(target, true) ?: return null
                    updateSlotType(resultSlot, SlotType.OBJ)
                    CompiledValue(resultSlot, SlotType.OBJ)
                }
                is net.sergeych.lyng.WhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitWhile(target, true) ?: return null
                        updateSlotType(resultSlot, SlotType.OBJ)
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitDoWhile(target, true) ?: return null
                        updateSlotType(resultSlot, SlotType.OBJ)
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is BlockStatement -> emitBlock(target, true)
                is VarDeclStatement -> emitVarDecl(target)
                is DelegatedVarDeclStatement -> emitDelegatedVarDecl(target)
                is DestructuringVarDeclStatement -> emitDestructuringVarDecl(target)
                is net.sergeych.lyng.ExtensionPropertyDeclStatement -> emitExtensionPropertyDecl(target)
                is net.sergeych.lyng.ClassDeclStatement -> emitStatementEval(target)
                is net.sergeych.lyng.FunctionDeclStatement -> emitStatementEval(target)
                is net.sergeych.lyng.EnumDeclStatement -> emitStatementEval(target)
                is net.sergeych.lyng.TryStatement -> emitTry(target, true)
                is net.sergeych.lyng.WhenStatement -> compileWhen(target, true)
                is net.sergeych.lyng.BreakStatement -> compileBreak(target)
                is net.sergeych.lyng.ContinueStatement -> compileContinue(target)
                is net.sergeych.lyng.ReturnStatement -> compileReturn(target)
                is net.sergeych.lyng.ThrowStatement -> compileThrow(target)
                is net.sergeych.lyng.TryStatement -> emitTry(target, false)
                else -> {
                    emitFallbackStatement(target)
                }
            }
        } else {
            when (target) {
                is ExpressionStatement -> {
                    val ref = target.ref
                    if (ref is IncDecRef) {
                        compileIncDec(ref, false)
                    } else {
                        compileRefWithFallback(ref, null, target.pos)
                    }
                }
                is VarDeclStatement -> emitVarDecl(target)
                is DelegatedVarDeclStatement -> emitDelegatedVarDecl(target)
                is IfStatement -> compileIfStatement(target)
                is net.sergeych.lyng.ForInStatement -> {
                    val resultSlot = emitForIn(target, false) ?: return null
                    CompiledValue(resultSlot, SlotType.OBJ)
                }
                is net.sergeych.lyng.WhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitWhile(target, false) ?: return null
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    if (!allowLocalSlots) emitFallbackStatement(target)
                    else {
                        val resultSlot = emitDoWhile(target, false) ?: return null
                        CompiledValue(resultSlot, SlotType.OBJ)
                    }
                }
                is BlockStatement -> emitBlock(target, false)
                is DestructuringVarDeclStatement -> emitDestructuringVarDecl(target)
                is net.sergeych.lyng.ExtensionPropertyDeclStatement -> emitExtensionPropertyDecl(target)
                is net.sergeych.lyng.BreakStatement -> compileBreak(target)
                is net.sergeych.lyng.ContinueStatement -> compileContinue(target)
                is net.sergeych.lyng.ReturnStatement -> compileReturn(target)
                is net.sergeych.lyng.ThrowStatement -> compileThrow(target)
                is net.sergeych.lyng.TryStatement -> emitTry(target, false)
                is net.sergeych.lyng.WhenStatement -> compileWhen(target, false)
                else -> {
                    emitFallbackStatement(target)
                }
            }
        }
    }

    private fun emitBlock(stmt: BlockStatement, needResult: Boolean): CompiledValue? {
        if (shouldInlineBlock(stmt)) {
            return emitInlineStatements(stmt.statements(), needResult)
        }
        val captureNames = if (stmt.captureSlots.isEmpty()) emptyList() else stmt.captureSlots.map { it.name }
        val planId = builder.addConst(BytecodeConst.SlotPlan(stmt.slotPlan, captureNames))
        builder.emit(Opcode.PUSH_SCOPE, planId)
        resetAddrCache()
        val statements = stmt.statements()
        var lastValue: CompiledValue? = null
        for ((index, statement) in statements.withIndex()) {
            val isLast = index == statements.lastIndex
            val wantResult = needResult && isLast
            val value = compileStatementValueOrFallback(statement, wantResult)
                ?: run {
                    val original = (statement as? BytecodeStatement)?.original
                    val name = original?.let { "${statement::class.simpleName}(${it::class.simpleName})" }
                        ?: statement::class.simpleName
                    throw BytecodeCompileException(
                        "Bytecode compile error: failed to compile block statement ($name)",
                        statement.pos
                    )
                }
            if (wantResult) {
                lastValue = value
            }
        }
        val result = if (needResult) {
            var value = lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
            if (value.slot < scopeSlotCount) {
                val captured = allocSlot()
                emitMove(value, captured)
                value = CompiledValue(captured, value.type)
            }
            value
        } else {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
        builder.emit(Opcode.POP_SCOPE)
        resetAddrCache()
        return result
    }

    private fun emitTry(stmt: net.sergeych.lyng.TryStatement, needResult: Boolean): CompiledValue? {
        val resultSlot = allocSlot()
        updateSlotType(resultSlot, SlotType.OBJ)
        val exceptionSlot = allocSlot()
        updateSlotType(exceptionSlot, SlotType.OBJ)
        val catchLabel = if (stmt.catches.isNotEmpty()) builder.label() else null
        val finallyLabel = if (stmt.finallyClause != null) builder.label() else null
        val endLabel = builder.label()
        val catchOperand = catchLabel?.let { CmdBuilder.Operand.LabelRef(it) }
            ?: CmdBuilder.Operand.IntVal(-1)
        val finallyOperand = finallyLabel?.let { CmdBuilder.Operand.LabelRef(it) }
            ?: CmdBuilder.Operand.IntVal(-1)
        builder.emit(
            Opcode.PUSH_TRY,
            listOf(
                CmdBuilder.Operand.IntVal(exceptionSlot),
                catchOperand,
                finallyOperand
            )
        )
        val bodyValue = compileStatementValueOrFallback(stmt.body, needResult) ?: return null
        if (needResult) {
            emitMove(bodyValue, resultSlot)
        }
        if (finallyLabel != null) {
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(finallyLabel)))
        } else {
            builder.emit(Opcode.POP_TRY)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        }
        if (catchLabel != null) {
            builder.mark(catchLabel)
            val catchBlockLabels = stmt.catches.map { builder.label() }
            val noMatchLabel = builder.label()
            for ((index, cdata) in stmt.catches.withIndex()) {
                val handlerLabel = catchBlockLabels[index]
                for (className in cdata.classNames) {
                    val classValue = compileCatchClassSlot(className) ?: return null
                    val checkSlot = allocSlot()
                    builder.emit(Opcode.CHECK_IS, exceptionSlot, classValue.slot, checkSlot)
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(checkSlot), CmdBuilder.Operand.LabelRef(handlerLabel))
                    )
                }
            }
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(noMatchLabel)))
            for ((index, cdata) in stmt.catches.withIndex()) {
                val handlerLabel = catchBlockLabels[index]
                builder.mark(handlerLabel)
                builder.emit(Opcode.CLEAR_PENDING_THROWABLE)
                val catchValue = emitCatchBlock(cdata.block, cdata.catchVarName, exceptionSlot, needResult)
                    ?: return null
                if (needResult) {
                    emitMove(catchValue, resultSlot)
                }
                if (finallyLabel != null) {
                    builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(finallyLabel)))
                } else {
                    builder.emit(Opcode.POP_TRY)
                    builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
                }
            }
            builder.mark(noMatchLabel)
            if (finallyLabel != null) {
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(finallyLabel)))
            } else {
                builder.emit(Opcode.POP_TRY)
                builder.emit(Opcode.RETHROW_PENDING)
            }
        }
        if (finallyLabel != null) {
            builder.mark(finallyLabel)
            builder.emit(Opcode.POP_TRY)
            stmt.finallyClause?.let { finallyClause ->
                compileStatementValueOrFallback(finallyClause, false) ?: return null
            }
            builder.emit(Opcode.RETHROW_PENDING)
        }
        builder.mark(endLabel)
        if (needResult) return CompiledValue(resultSlot, SlotType.OBJ)
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        val voidSlot = allocSlot()
        builder.emit(Opcode.CONST_OBJ, voidId, voidSlot)
        return CompiledValue(voidSlot, SlotType.OBJ)
    }

    private fun emitCatchBlock(
        block: Statement,
        catchVarName: String,
        exceptionSlot: Int,
        needResult: Boolean
    ): CompiledValue? {
        val stmt = block as? BlockStatement
        if (stmt == null) {
            val declId = builder.addConst(BytecodeConst.LocalDecl(catchVarName, false, Visibility.Public, false))
            builder.emit(Opcode.DECL_LOCAL, declId, exceptionSlot)
            return compileStatementValueOrFallback(block, needResult)
        }
        val captureNames = if (stmt.captureSlots.isEmpty()) emptyList() else stmt.captureSlots.map { it.name }
        val planId = builder.addConst(BytecodeConst.SlotPlan(stmt.slotPlan, captureNames))
        builder.emit(Opcode.PUSH_SCOPE, planId)
        resetAddrCache()
        val declId = builder.addConst(BytecodeConst.LocalDecl(catchVarName, false, Visibility.Public, false))
        builder.emit(Opcode.DECL_LOCAL, declId, exceptionSlot)
        val catchSlotIndex = stmt.slotPlan[catchVarName]
        if (catchSlotIndex != null) {
            val key = ScopeSlotKey(stmt.scopeId, catchSlotIndex)
            val localIndex = localSlotIndexByKey[key]
            if (localIndex != null) {
                val localSlot = scopeSlotCount + localIndex
                if (localSlot != exceptionSlot) {
                    emitMove(CompiledValue(exceptionSlot, SlotType.OBJ), localSlot)
                }
                updateSlotType(localSlot, SlotType.OBJ)
            }
        }
        val statements = stmt.statements()
        var lastValue: CompiledValue? = null
        for ((index, statement) in statements.withIndex()) {
            val isLast = index == statements.lastIndex
            val wantResult = needResult && isLast
            val value = compileStatementValueOrFallback(statement, wantResult) ?: return null
            if (wantResult) {
                lastValue = value
            }
        }
        val result = if (needResult) {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        } else {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
        builder.emit(Opcode.POP_SCOPE)
        resetAddrCache()
        return result
    }

    private fun compileCatchClassSlot(name: String): CompiledValue? {
        val ref = LocalVarRef(name, Pos.builtIn)
        val compiled = compileRef(ref)
        if (compiled != null) {
            return ensureObjSlot(compiled)
        }
        val cls = nameObjClass[name] ?: resolveTypeNameClass(name) ?: return null
        val id = builder.addConst(BytecodeConst.ObjRef(cls))
        val slot = allocSlot()
        builder.emit(Opcode.CONST_OBJ, id, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun emitInlineStatements(statements: List<Statement>, needResult: Boolean): CompiledValue? {
        var lastValue: CompiledValue? = null
        for ((index, statement) in statements.withIndex()) {
            val isLast = index == statements.lastIndex
            val wantResult = needResult && isLast
            val value = compileStatementValueOrFallback(statement, wantResult) ?: return null
            if (wantResult) {
                lastValue = value
            }
        }
        return if (needResult) {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        } else {
            lastValue ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                CompiledValue(slot, SlotType.OBJ)
            }
        }
    }

    private fun emitInlineBlock(stmt: BlockStatement, needResult: Boolean): CompiledValue? =
        emitInlineStatements(stmt.statements(), needResult)

    private fun shouldInlineBlock(stmt: BlockStatement): Boolean {
        return allowLocalSlots && !forceScopeSlots
    }

    private fun compileInlineBlock(name: String, stmt: net.sergeych.lyng.InlineBlockStatement): CmdFunction? {
        val result = emitInlineStatements(stmt.statements(), true) ?: return null
        builder.emit(Opcode.RET, result.slot)
        val localCount = maxOf(nextSlot, result.slot + 1) - scopeSlotCount
        return builder.build(
            name,
            localCount,
            addrCount = nextAddrSlot,
            returnLabels = returnLabels,
            scopeSlotIndices,
            scopeSlotNames,
            scopeSlotIsModule,
            localSlotNames,
            localSlotMutables
        )
    }

    private fun compileLoopBody(stmt: Statement, needResult: Boolean): CompiledValue? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        if (target is BlockStatement) {
            val useInline = !forceScopeSlots && target.slotPlan.isEmpty() && target.captureSlots.isEmpty()
            return if (useInline) emitInlineBlock(target, needResult) else emitBlock(target, needResult)
        }
        return compileStatementValueOrFallback(target, needResult)
    }

    private fun emitVarDecl(stmt: VarDeclStatement): CompiledValue? {
        updateNameObjClass(stmt.name, stmt.initializer, stmt.initializerObjClass)
        val scopeId = stmt.scopeId ?: 0
        val isModuleSlot = isModuleSlot(scopeId, stmt.name)
        val scopeSlot = stmt.slotIndex?.let { slotIndex ->
            val key = ScopeSlotKey(scopeId, slotIndex)
            scopeSlotMap[key]
        } ?: run {
            if (isModuleSlot) {
                scopeSlotIndexByName[stmt.name]
            } else {
                null
            }
        }
        if (isModuleSlot && scopeSlot != null) {
            val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
                val unsetId = builder.addConst(BytecodeConst.ObjRef(ObjUnset))
                builder.emit(Opcode.CONST_OBJ, unsetId, scopeSlot)
                updateSlotType(scopeSlot, SlotType.OBJ)
                CompiledValue(scopeSlot, SlotType.OBJ)
            }
            if (value.slot != scopeSlot) {
                emitMove(value, scopeSlot)
            }
            updateSlotType(scopeSlot, value.type)
            updateNameObjClassFromSlot(stmt.name, scopeSlot)
            updateSlotObjClass(scopeSlot, stmt.initializer, stmt.initializerObjClass)
            val declId = builder.addConst(
                BytecodeConst.LocalDecl(
                    stmt.name,
                    stmt.isMutable,
                    stmt.visibility,
                    stmt.isTransient
                )
            )
            builder.emit(Opcode.DECL_LOCAL, declId, scopeSlot)
            return CompiledValue(scopeSlot, value.type)
        }
        val localSlot = if (allowLocalSlots && stmt.slotIndex != null) {
            val key = ScopeSlotKey(scopeId, stmt.slotIndex)
            val localIndex = localSlotIndexByKey[key]
            localIndex?.let { scopeSlotCount + it }
        } else {
            null
        }
        if (localSlot != null) {
            val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
                val unsetId = builder.addConst(BytecodeConst.ObjRef(ObjUnset))
                builder.emit(Opcode.CONST_OBJ, unsetId, localSlot)
                updateSlotType(localSlot, SlotType.OBJ)
                CompiledValue(localSlot, SlotType.OBJ)
            }
            if (value.slot != localSlot) {
                emitMove(value, localSlot)
            }
            updateSlotType(localSlot, value.type)
            updateSlotObjClass(localSlot, stmt.initializer, stmt.initializerObjClass)
            updateNameObjClassFromSlot(stmt.name, localSlot)
            val shadowedScopeSlot = scopeSlotIndexByName.containsKey(stmt.name)
            if (forceScopeSlots || !shadowedScopeSlot) {
                val declId = builder.addConst(
                    BytecodeConst.LocalDecl(
                        stmt.name,
                        stmt.isMutable,
                        stmt.visibility,
                        stmt.isTransient
                    )
                )
                builder.emit(Opcode.DECL_LOCAL, declId, localSlot)
            }
            return CompiledValue(localSlot, value.type)
        }
        if (scopeSlot != null) {
            val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
                val unsetId = builder.addConst(BytecodeConst.ObjRef(ObjUnset))
                builder.emit(Opcode.CONST_OBJ, unsetId, scopeSlot)
                updateSlotType(scopeSlot, SlotType.OBJ)
                CompiledValue(scopeSlot, SlotType.OBJ)
            }
            if (value.slot != scopeSlot) {
                emitMove(value, scopeSlot)
            }
            updateSlotType(scopeSlot, value.type)
            updateNameObjClassFromSlot(stmt.name, scopeSlot)
            updateSlotObjClass(scopeSlot, stmt.initializer, stmt.initializerObjClass)
            val declId = builder.addConst(
                BytecodeConst.LocalDecl(
                    stmt.name,
                    stmt.isMutable,
                    stmt.visibility,
                    stmt.isTransient
                )
            )
            builder.emit(Opcode.DECL_LOCAL, declId, scopeSlot)
            return CompiledValue(scopeSlot, value.type)
        }
        val value = stmt.initializer?.let { compileStatementValueOrFallback(it) } ?: run {
            val slot = allocSlot()
            val unsetId = builder.addConst(BytecodeConst.ObjRef(ObjUnset))
            builder.emit(Opcode.CONST_OBJ, unsetId, slot)
            updateSlotType(slot, SlotType.OBJ)
            CompiledValue(slot, SlotType.OBJ)
        }
        val declId = builder.addConst(
            BytecodeConst.LocalDecl(
                stmt.name,
                stmt.isMutable,
                stmt.visibility,
                stmt.isTransient
            )
        )
        builder.emit(Opcode.DECL_LOCAL, declId, value.slot)
        if (value.type != SlotType.UNKNOWN) {
            updateSlotTypeByName(stmt.name, value.type)
        }
        updateNameObjClassFromSlot(stmt.name, value.slot)
        updateSlotObjClass(value.slot, stmt.initializer, stmt.initializerObjClass)
        return value
    }

    private fun emitDelegatedVarDecl(stmt: DelegatedVarDeclStatement): CompiledValue? {
        val value = compileStatementValueOrFallback(stmt.initializer) ?: return null
        val declId = builder.addConst(
            BytecodeConst.DelegatedDecl(
                stmt.name,
                stmt.isMutable,
                stmt.visibility,
                stmt.isTransient
            )
        )
        builder.emit(Opcode.DECL_DELEGATED, declId, value.slot)
        updateSlotType(value.slot, SlotType.OBJ)
        return CompiledValue(value.slot, SlotType.OBJ)
    }

    private fun emitDestructuringVarDecl(stmt: DestructuringVarDeclStatement): CompiledValue? {
        val value = compileStatementValueOrFallback(stmt.initializer) ?: return null
        val declId = builder.addConst(
            BytecodeConst.DestructureDecl(
                stmt.pattern,
                stmt.names,
                stmt.isMutable,
                stmt.visibility,
                stmt.isTransient,
                stmt.pos
            )
        )
        builder.emit(Opcode.DECL_DESTRUCTURE, declId, value.slot)
        updateSlotType(value.slot, SlotType.OBJ)
        return CompiledValue(value.slot, SlotType.OBJ)
    }

    private fun updateNameObjClass(name: String, initializer: Statement?, initializerObjClass: ObjClass? = null) {
        val cls = initializerObjClass ?: objClassForInitializer(initializer)
        if (cls != null) {
            nameObjClass[name] = cls
        } else {
            nameObjClass.remove(name)
        }
    }

    private fun updateSlotObjClass(slot: Int, initializer: Statement?, initializerObjClass: ObjClass? = null) {
        val cls = initializerObjClass ?: objClassForInitializer(initializer)
        if (cls != null) {
            slotObjClass[slot] = cls
        }
    }

    private fun updateNameObjClassFromSlot(name: String, slot: Int) {
        val cls = slotObjClass[slot] ?: return
        nameObjClass[name] = cls
    }

    private fun objClassForInitializer(initializer: Statement?): ObjClass? {
        var initStmt = initializer
        if (initStmt is BytecodeStatement) {
            val fn = initStmt.bytecodeFunction()
            if (fn.cmds.any { it is CmdListLiteral }) return ObjList.type
            if (fn.cmds.any { it is CmdMakeRange || it is CmdRangeIntBounds }) return ObjRange.type
        }
        while (initStmt is BytecodeStatement) {
            initStmt = initStmt.original
        }
        val initRef = (initStmt as? ExpressionStatement)?.ref
        val directRef = when (initRef) {
            is StatementRef -> (initRef.statement as? ExpressionStatement)?.ref
            else -> initRef
        }
        return when (directRef) {
            is ListLiteralRef -> ObjList.type
            is MapLiteralRef -> ObjMap.type
            is RangeRef -> ObjRange.type
            is ImplicitThisMethodCallRef -> {
                if (directRef.methodName() == "iterator") ObjIterator else null
            }
            is ThisMethodSlotCallRef -> {
                if (directRef.methodName() == "iterator") ObjIterator else null
            }
            is MethodCallRef -> {
                if (directRef.name == "iterator") ObjIterator else null
            }
            is CallRef -> {
                val target = directRef.target
                when {
                    target is LocalVarRef && target.name == "List" -> ObjList.type
                    target is LocalVarRef && target.name == "Map" -> ObjMap.type
                    target is LocalVarRef && target.name == "iterator" -> ObjIterator
                    target is ImplicitThisMemberRef && target.name == "iterator" -> ObjIterator
                    target is ThisFieldSlotRef && target.name == "iterator" -> ObjIterator
                    target is FieldRef && target.name == "iterator" -> ObjIterator
                    else -> null
                }
            }
            is ConstRef -> when (directRef.constValue) {
                is ObjList -> ObjList.type
                is ObjMap -> ObjMap.type
                is ObjRange -> ObjRange.type
                else -> null
            }
            else -> null
        }
    }
    private fun emitForIn(stmt: net.sergeych.lyng.ForInStatement, wantResult: Boolean): Int? {
        val range = stmt.constRange
        var rangeRef = if (range == null) extractRangeRef(stmt.source) else null
        if (range == null && rangeRef == null) {
            rangeRef = extractRangeFromLocal(stmt.source)
        }
        val typedRangeLocal = if (range == null && rangeRef == null) extractTypedRangeLocal(stmt.source) else null
        val loopSlotPlan = stmt.loopSlotPlan
        var useLoopScope = loopSlotPlan.isNotEmpty()
        val loopLocalIndex = localSlotIndexByName[stmt.loopVarName]
        var usedOverride = false
        var loopSlotId = when {
            loopLocalIndex != null -> scopeSlotCount + loopLocalIndex
            else -> {
                val localKey = localSlotInfoMap.entries.firstOrNull { it.value.name == stmt.loopVarName }?.key
                val localIndex = localKey?.let { localSlotIndexByKey[it] }
                when {
                    localIndex != null -> scopeSlotCount + localIndex
                    else -> scopeSlotIndexByName[stmt.loopVarName]
                }
            }
        } ?: run {
            val slot = allocSlot()
            loopSlotOverrides[stmt.loopVarName] = slot
            usedOverride = true
            slot
        }
        var emitDeclLocal = usedOverride
        if (useLoopScope && !forceScopeSlots) {
            val loopVarOnly = loopSlotPlan.size == 1 && loopSlotPlan.containsKey(stmt.loopVarName)
            val loopVarIsLocal = loopSlotId >= scopeSlotCount
            if (loopVarOnly && loopVarIsLocal) {
                useLoopScope = false
            }
        }
        if (useLoopScope && allowLocalSlots && !forceScopeSlots) {
            val needsScope = allowedScopeNames?.let { names ->
                loopSlotPlan.keys.any { names.contains(it) }
            } == true
            if (!needsScope) {
                useLoopScope = false
            }
        }
        emitDeclLocal = emitDeclLocal && useLoopScope
        if (!forceScopeSlots && loopSlotId < scopeSlotCount) {
            val localSlot = allocSlot()
            loopSlotOverrides[stmt.loopVarName] = localSlot
            usedOverride = true
            emitDeclLocal = false
            loopSlotId = localSlot
        }
        val planId = if (useLoopScope) {
            builder.addConst(BytecodeConst.SlotPlan(loopSlotPlan, emptyList()))
        } else {
            -1
        }
        val loopDeclId = if (emitDeclLocal) {
            builder.addConst(
                BytecodeConst.LocalDecl(
                    stmt.loopVarName,
                    true,
                    Visibility.Public,
                    isTransient = false
                )
            )
        } else {
            -1
        }

        try {
        if (range == null && rangeRef == null && typedRangeLocal == null) {
            val sourceValue = compileStatementValueOrFallback(stmt.source) ?: return null
            val sourceObj = ensureObjSlot(sourceValue)
            val typeId = builder.addConst(BytecodeConst.ObjRef(ObjIterable))
            val typeSlot = allocSlot()
            builder.emit(Opcode.CONST_OBJ, typeId, typeSlot)
            builder.emit(Opcode.ASSERT_IS, sourceObj.slot, typeSlot)

            val iterableMethods = ObjIterable.instanceMethodIdMap(includeAbstract = true)
            val iteratorMethodId = iterableMethods["iterator"]
            if (iteratorMethodId == null) {
                throw BytecodeCompileException("Missing member id for Iterable.iterator", stmt.pos)
            }
            val iteratorMethods = ObjIterator.instanceMethodIdMap(includeAbstract = true)
            val hasNextMethodId = iteratorMethods["hasNext"]
            if (hasNextMethodId == null) {
                throw BytecodeCompileException("Missing member id for Iterator.hasNext", stmt.pos)
            }
            val nextMethodId = iteratorMethods["next"]
            if (nextMethodId == null) {
                throw BytecodeCompileException("Missing member id for Iterator.next", stmt.pos)
            }

            val iterSlot = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, sourceObj.slot, iteratorMethodId, 0, 0, iterSlot)
            builder.emit(Opcode.ITER_PUSH, iterSlot)

            val breakFlagSlot = allocSlot()
            val falseId = builder.addConst(BytecodeConst.Bool(false))
            builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

            val resultSlot = allocSlot()
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

            val loopLabel = builder.label()
            val continueLabel = builder.label()
            val endLabel = builder.label()
            if (useLoopScope) {
                builder.emit(Opcode.PUSH_SCOPE, planId)
                resetAddrCache()
            }
            builder.mark(loopLabel)

            val hasNextSlot = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, iterSlot, hasNextMethodId, 0, 0, hasNextSlot)
            val condSlot = allocSlot()
            builder.emit(Opcode.OBJ_TO_BOOL, hasNextSlot, condSlot)
            builder.emit(
                Opcode.JMP_IF_FALSE,
                listOf(CmdBuilder.Operand.IntVal(condSlot), CmdBuilder.Operand.LabelRef(endLabel))
            )

            val nextSlot = allocSlot()
            builder.emit(Opcode.CALL_MEMBER_SLOT, iterSlot, nextMethodId, 0, 0, nextSlot)
            val nextObj = ensureObjSlot(CompiledValue(nextSlot, SlotType.UNKNOWN))
            builder.emit(Opcode.MOVE_OBJ, nextObj.slot, loopSlotId)
            updateSlotType(loopSlotId, SlotType.OBJ)
            updateSlotTypeByName(stmt.loopVarName, SlotType.OBJ)
            if (emitDeclLocal) {
                builder.emit(Opcode.DECL_LOCAL, loopDeclId, loopSlotId)
            }

            loopStack.addLast(
                LoopContext(
                    stmt.label,
                    endLabel,
                    continueLabel,
                    breakFlagSlot,
                    if (wantResult) resultSlot else null,
                    hasIterator = true
                )
            )
            val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
            loopStack.removeLast()
            if (wantResult) {
                val bodyObj = ensureObjSlot(bodyValue)
                builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
            }
            builder.mark(continueLabel)
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

            builder.mark(endLabel)
            val afterPop = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterPop))
            )
            builder.emit(Opcode.ITER_POP)
            builder.mark(afterPop)
            if (stmt.elseStatement != null) {
                val afterElse = builder.label()
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
                )
                val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
                if (wantResult) {
                    val elseObj = ensureObjSlot(elseValue)
                    builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
                }
                builder.mark(afterElse)
            }
            if (useLoopScope) {
                builder.emit(Opcode.POP_SCOPE)
                resetAddrCache()
            }
            return resultSlot
        }

        val iSlot = allocSlot()
        val endSlot = allocSlot()
        if (range != null) {
            val startId = builder.addConst(BytecodeConst.IntVal(range.start))
            val endId = builder.addConst(BytecodeConst.IntVal(range.endExclusive))
            builder.emit(Opcode.CONST_INT, startId, iSlot)
            builder.emit(Opcode.CONST_INT, endId, endSlot)
        } else {
            if (rangeRef != null) {
                val left = rangeRef.left ?: return null
                val right = rangeRef.right ?: return null
                val startValue = compileRef(left) ?: return null
                val endValue = compileRef(right) ?: return null
                if (startValue.type != SlotType.INT || endValue.type != SlotType.INT) return null
                emitMove(startValue, iSlot)
                emitMove(endValue, endSlot)
                if (rangeRef.isEndInclusive) {
                    builder.emit(Opcode.INC_INT, endSlot)
                }
            } else {
                val rangeLocal = typedRangeLocal ?: return null
                val rangeValue = compileRef(rangeLocal) ?: return null
                val rangeObj = ensureObjSlot(rangeValue)
                val okSlot = allocSlot()
                builder.emit(Opcode.RANGE_INT_BOUNDS, rangeObj.slot, iSlot, endSlot, okSlot)
                val badRangeLabel = builder.label()
                builder.emit(
                    Opcode.JMP_IF_FALSE,
                    listOf(CmdBuilder.Operand.IntVal(okSlot), CmdBuilder.Operand.LabelRef(badRangeLabel))
                )
                val breakFlagSlot = allocSlot()
                val falseId = builder.addConst(BytecodeConst.Bool(false))
                builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

                val resultSlot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

                val loopLabel = builder.label()
                val continueLabel = builder.label()
                val endLabel = builder.label()
                val doneLabel = builder.label()
                if (useLoopScope) {
                    builder.emit(Opcode.PUSH_SCOPE, planId)
                    resetAddrCache()
                }
                builder.mark(loopLabel)
                val cmpSlot = allocSlot()
                builder.emit(Opcode.CMP_GTE_INT, iSlot, endSlot, cmpSlot)
                builder.emit(
                    Opcode.JMP_IF_TRUE,
                    listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
                )
                builder.emit(Opcode.MOVE_INT, iSlot, loopSlotId)
                updateSlotType(loopSlotId, SlotType.INT)
                updateSlotTypeByName(stmt.loopVarName, SlotType.INT)
                if (emitDeclLocal) {
                    builder.emit(Opcode.DECL_LOCAL, loopDeclId, loopSlotId)
                }
                loopStack.addLast(
                    LoopContext(
                        stmt.label,
                        endLabel,
                        continueLabel,
                        breakFlagSlot,
                        if (wantResult) resultSlot else null,
                        hasIterator = false
                    )
                )
                val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
                loopStack.removeLast()
                if (wantResult) {
                    val bodyObj = ensureObjSlot(bodyValue)
                    builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
                }
                builder.mark(continueLabel)
                builder.emit(Opcode.INC_INT, iSlot)
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

                builder.mark(endLabel)
                if (stmt.elseStatement != null) {
                    val afterElse = builder.label()
                    builder.emit(
                        Opcode.JMP_IF_TRUE,
                        listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
                    )
                    val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
                    if (wantResult) {
                        val elseObj = ensureObjSlot(elseValue)
                        builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
                    }
                    builder.mark(afterElse)
                }
                if (useLoopScope) {
                    builder.emit(Opcode.POP_SCOPE)
                    resetAddrCache()
                }
                builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(doneLabel)))
                builder.mark(badRangeLabel)
                val msgId = builder.addConst(BytecodeConst.StringVal("expected Int range"))
                builder.emit(Opcode.CONST_OBJ, msgId, resultSlot)
                val posId = builder.addConst(BytecodeConst.PosVal(stmt.pos))
                builder.emit(Opcode.THROW, posId, resultSlot)
                builder.mark(doneLabel)
                return resultSlot
            }
        }

        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
        val endLabel = builder.label()
        if (useLoopScope) {
            builder.emit(Opcode.PUSH_SCOPE, planId)
            resetAddrCache()
        }
        builder.mark(loopLabel)
        val cmpSlot = allocSlot()
        builder.emit(Opcode.CMP_GTE_INT, iSlot, endSlot, cmpSlot)
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(cmpSlot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        builder.emit(Opcode.MOVE_INT, iSlot, loopSlotId)
        updateSlotType(loopSlotId, SlotType.INT)
        updateSlotTypeByName(stmt.loopVarName, SlotType.INT)
        if (emitDeclLocal) {
            builder.emit(Opcode.DECL_LOCAL, loopDeclId, loopSlotId)
        }
        loopStack.addLast(
            LoopContext(
                stmt.label,
                endLabel,
                continueLabel,
                breakFlagSlot,
                if (wantResult) resultSlot else null,
                hasIterator = false
            )
        )
        val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        builder.emit(Opcode.INC_INT, iSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
        }
        if (useLoopScope) {
            builder.emit(Opcode.POP_SCOPE)
            resetAddrCache()
        }
        return resultSlot
        } finally {
            if (usedOverride) {
                loopSlotOverrides.remove(stmt.loopVarName)
            }
        }
    }

    private fun emitWhile(stmt: net.sergeych.lyng.WhileStatement, wantResult: Boolean): Int? {
        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
        val endLabel = builder.label()
        builder.mark(loopLabel)
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(endLabel))
        )
        loopStack.addLast(
            LoopContext(
                stmt.label,
                endLabel,
                continueLabel,
                breakFlagSlot,
                if (wantResult) resultSlot else null,
                hasIterator = false
            )
        )
        val bodyValue = compileLoopBody(stmt.body, wantResult) ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(loopLabel)))

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
        }
        return resultSlot
    }

    private fun emitDoWhile(stmt: net.sergeych.lyng.DoWhileStatement, wantResult: Boolean): Int? {
        val breakFlagSlot = allocSlot()
        val falseId = builder.addConst(BytecodeConst.Bool(false))
        builder.emit(Opcode.CONST_BOOL, falseId, breakFlagSlot)

        val resultSlot = allocSlot()
        val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
        builder.emit(Opcode.CONST_OBJ, voidId, resultSlot)

        val loopLabel = builder.label()
        val continueLabel = builder.label()
        val endLabel = builder.label()
        val useLoopScope = stmt.loopSlotPlan.isNotEmpty()
        val breakLabel = if (useLoopScope) builder.label() else endLabel
        val planId = if (useLoopScope) {
            builder.addConst(BytecodeConst.SlotPlan(stmt.loopSlotPlan, emptyList()))
        } else {
            -1
        }
        builder.mark(loopLabel)
        if (useLoopScope) {
            builder.emit(Opcode.PUSH_SCOPE, planId)
            resetAddrCache()
        }
        loopStack.addLast(
            LoopContext(
                stmt.label,
                breakLabel,
                continueLabel,
                breakFlagSlot,
                if (wantResult) resultSlot else null,
                hasIterator = false
            )
        )
        val bodyTarget = if (stmt.body is BytecodeStatement) stmt.body.original else stmt.body
        val bodyValue = if (useLoopScope && bodyTarget is BlockStatement) {
            emitInlineBlock(bodyTarget, wantResult)
        } else {
            compileStatementValueOrFallback(stmt.body, wantResult)
        } ?: return null
        loopStack.removeLast()
        if (wantResult) {
            val bodyObj = ensureObjSlot(bodyValue)
            builder.emit(Opcode.MOVE_OBJ, bodyObj.slot, resultSlot)
        }
        builder.mark(continueLabel)
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        if (useLoopScope) {
            builder.emit(Opcode.POP_SCOPE)
            resetAddrCache()
        }
        builder.emit(
            Opcode.JMP_IF_TRUE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(loopLabel))
        )
        if (useLoopScope) {
            builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
            builder.mark(breakLabel)
            builder.emit(Opcode.POP_SCOPE)
            resetAddrCache()
        }

        builder.mark(endLabel)
        if (stmt.elseStatement != null) {
            val afterElse = builder.label()
            builder.emit(
                Opcode.JMP_IF_TRUE,
                listOf(CmdBuilder.Operand.IntVal(breakFlagSlot), CmdBuilder.Operand.LabelRef(afterElse))
            )
            val elseValue = compileStatementValueOrFallback(stmt.elseStatement, wantResult) ?: return null
            if (wantResult) {
                val elseObj = ensureObjSlot(elseValue)
                builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
            }
            builder.mark(afterElse)
        }
        return resultSlot
    }

    private fun compileIfStatement(stmt: IfStatement): CompiledValue? {
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenRestore = applyFlowTypeOverride(flowTypeOverrideForIf(stmt.condition, applyForThen = true))
        compileStatementValueOrFallback(stmt.ifBody, false) ?: return null
        restoreFlowTypeOverride(thenRestore)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        stmt.elseBody?.let {
            val elseRestore = applyFlowTypeOverride(flowTypeOverrideForIf(stmt.condition, applyForThen = false))
            compileStatementValueOrFallback(it, false) ?: return null
            restoreFlowTypeOverride(elseRestore)
        }
        builder.mark(endLabel)
        return condition
    }

    private fun updateSlotTypeByName(name: String, type: SlotType) {
        val localIndex = localSlotIndexByName[name]
        if (localIndex != null) {
            updateSlotType(scopeSlotCount + localIndex, type)
            return
        }
        for ((key, index) in scopeSlotMap) {
            if (scopeSlotNameMap[key] == name) {
                updateSlotType(index, type)
            }
        }
    }

    private fun compileIfExpression(stmt: IfStatement): CompiledValue? {
        val condition = compileCondition(stmt.condition, stmt.pos) ?: return null
        if (condition.type != SlotType.BOOL) return null
        val resultSlot = allocSlot()
        val elseLabel = builder.label()
        val endLabel = builder.label()
        builder.emit(
            Opcode.JMP_IF_FALSE,
            listOf(CmdBuilder.Operand.IntVal(condition.slot), CmdBuilder.Operand.LabelRef(elseLabel))
        )
        val thenRestore = applyFlowTypeOverride(flowTypeOverrideForIf(stmt.condition, applyForThen = true))
        val thenValue = compileStatementValueOrFallback(stmt.ifBody) ?: return null
        restoreFlowTypeOverride(thenRestore)
        val thenObj = ensureObjSlot(thenValue)
        builder.emit(Opcode.MOVE_OBJ, thenObj.slot, resultSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(endLabel)))
        builder.mark(elseLabel)
        if (stmt.elseBody != null) {
            val elseRestore = applyFlowTypeOverride(flowTypeOverrideForIf(stmt.condition, applyForThen = false))
            val elseValue = compileStatementValueOrFallback(stmt.elseBody) ?: return null
            restoreFlowTypeOverride(elseRestore)
            val elseObj = ensureObjSlot(elseValue)
            builder.emit(Opcode.MOVE_OBJ, elseObj.slot, resultSlot)
        } else {
            val id = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, id, resultSlot)
        }
        builder.mark(endLabel)
        updateSlotType(resultSlot, SlotType.OBJ)
        return CompiledValue(resultSlot, SlotType.OBJ)
    }

    private fun compileCondition(stmt: Statement, pos: Pos): CompiledValue? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        return when (target) {
            is ExpressionStatement -> compileRefWithFallback(target.ref, SlotType.BOOL, target.pos)
            else -> {
                throw BytecodeCompileException(
                    "Bytecode compile error: unsupported condition",
                    pos
                )
            }
        }
    }

    private data class FlowTypeSubject(val name: String, val slot: Int?)
    private data class FlowTypeInfo(val name: String, val slot: Int?, val cls: ObjClass)
    private data class FlowTypeRestore(
        val name: String,
        val prevNameClass: ObjClass?,
        val slot: Int?,
        val prevSlotClass: ObjClass?,
    )

    private fun extractFlowTypeSubject(stmt: Statement): FlowTypeSubject? {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        val expr = target as? ExpressionStatement ?: return null
        return flowTypeSubjectFromRef(expr.ref)
    }

    private fun flowTypeSubjectFromRef(ref: ObjRef): FlowTypeSubject? {
        return when (ref) {
            is LocalSlotRef -> FlowTypeSubject(ref.name, resolveSlot(ref))
            is LocalVarRef -> FlowTypeSubject(ref.name, resolveDirectNameSlot(ref.name)?.slot)
            else -> null
        }
    }

    private fun flowTypeOverrideForIf(condition: Statement, applyForThen: Boolean): FlowTypeInfo? {
        val target = if (condition is BytecodeStatement) condition.original else condition
        val expr = target as? ExpressionStatement ?: return null
        val ref = expr.ref as? BinaryOpRef ?: return null
        val op = binaryOp(ref)
        val apply = when (op) {
            BinOp.IS -> applyForThen
            BinOp.NOTIS -> !applyForThen
            else -> false
        }
        if (!apply) return null
        val cls = resolveTypeRefClass(binaryRight(ref)) ?: return null
        return flowTypeInfoForRef(binaryLeft(ref), cls)
    }

    private fun flowTypeOverrideForWhenCase(
        subject: FlowTypeSubject?,
        conditions: List<WhenCondition>
    ): FlowTypeInfo? {
        if (subject == null || conditions.size != 1) return null
        val cond = conditions.first() as? WhenIsCondition ?: return null
        if (cond.negated) return null
        val expr = cond.expr as? ExpressionStatement ?: return null
        val cls = resolveTypeRefClass(expr.ref) ?: return null
        return FlowTypeInfo(subject.name, subject.slot, cls)
    }

    private fun flowTypeInfoForRef(ref: ObjRef, cls: ObjClass): FlowTypeInfo? {
        return when (ref) {
            is LocalSlotRef -> FlowTypeInfo(ref.name, resolveSlot(ref), cls)
            is LocalVarRef -> FlowTypeInfo(ref.name, resolveDirectNameSlot(ref.name)?.slot, cls)
            else -> null
        }
    }

    private fun applyFlowTypeOverride(info: FlowTypeInfo?): FlowTypeRestore? {
        if (info == null) return null
        val prevNameClass = nameObjClass[info.name]
        nameObjClass[info.name] = info.cls
        val prevSlotClass = info.slot?.let { slotObjClass[it] }
        if (info.slot != null) {
            slotObjClass[info.slot] = info.cls
        }
        return FlowTypeRestore(info.name, prevNameClass, info.slot, prevSlotClass)
    }

    private fun restoreFlowTypeOverride(restore: FlowTypeRestore?) {
        if (restore == null) return
        if (restore.prevNameClass == null) {
            nameObjClass.remove(restore.name)
        } else {
            nameObjClass[restore.name] = restore.prevNameClass
        }
        if (restore.slot != null) {
            if (restore.prevSlotClass == null) {
                slotObjClass.remove(restore.slot)
            } else {
                slotObjClass[restore.slot] = restore.prevSlotClass
            }
        }
    }

    private fun findLoopContextIndex(label: String?): Int? {
        if (loopStack.isEmpty()) return null
        val stack = loopStack.toList()
        if (label == null) return stack.lastIndex
        for (i in stack.indices.reversed()) {
            if (stack[i].label == label) return i
        }
        return null
    }

    private fun emitIteratorCancel(stack: List<LoopContext>, startIndex: Int) {
        for (i in stack.lastIndex downTo startIndex) {
            if (stack[i].hasIterator) {
                builder.emit(Opcode.ITER_CANCEL)
            }
        }
    }

    private fun compileBreak(stmt: net.sergeych.lyng.BreakStatement): CompiledValue? {
        val stack = loopStack.toList()
        val targetIndex = findLoopContextIndex(stmt.label) ?: run {
            val labels = stack.joinToString(prefix = "[", postfix = "]") { it.label ?: "<unlabeled>" }
            throw BytecodeCompileException(
                "Bytecode compile error: break label '${stmt.label}' not found in $labels",
                stmt.pos
            )
        }
        val ctx = stack[targetIndex]
        val value = stmt.resultExpr?.let { compileStatementValueOrFallback(it) }
        if (ctx.resultSlot != null) {
            val objValue = value?.let { ensureObjSlot(it) } ?: run {
                val slot = allocSlot()
                val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
                builder.emit(Opcode.CONST_OBJ, voidId, slot)
                updateSlotType(slot, SlotType.OBJ)
                CompiledValue(slot, SlotType.OBJ)
            }
            builder.emit(Opcode.MOVE_OBJ, objValue.slot, ctx.resultSlot)
        } else if (value != null) {
            ensureObjSlot(value)
        }
        emitIteratorCancel(stack, targetIndex)
        val trueId = builder.addConst(BytecodeConst.Bool(true))
        builder.emit(Opcode.CONST_BOOL, trueId, ctx.breakFlagSlot)
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(ctx.breakLabel)))
        return CompiledValue(ctx.breakFlagSlot, SlotType.BOOL)
    }

    private fun compileContinue(stmt: net.sergeych.lyng.ContinueStatement): CompiledValue? {
        val stack = loopStack.toList()
        val targetIndex = findLoopContextIndex(stmt.label) ?: return null
        val ctx = stack[targetIndex]
        if (targetIndex < stack.lastIndex) {
            emitIteratorCancel(stack, targetIndex + 1)
        }
        builder.emit(Opcode.JMP, listOf(CmdBuilder.Operand.LabelRef(ctx.continueLabel)))
        return CompiledValue(ctx.breakFlagSlot, SlotType.BOOL)
    }

    private fun compileReturn(stmt: net.sergeych.lyng.ReturnStatement): CompiledValue? {
        val value = stmt.resultExpr?.let { compileStatementValueOrFallback(it) } ?: run {
            val slot = allocSlot()
            val voidId = builder.addConst(BytecodeConst.ObjRef(ObjVoid))
            builder.emit(Opcode.CONST_OBJ, voidId, slot)
            updateSlotType(slot, SlotType.OBJ)
            CompiledValue(slot, SlotType.OBJ)
        }
        val label = stmt.label
        if (label == null || returnLabels.contains(label)) {
            builder.emit(Opcode.RET, value.slot)
        } else {
            val labelId = builder.addConst(BytecodeConst.StringVal(label))
            builder.emit(Opcode.RET_LABEL, labelId, value.slot)
        }
        return value
    }

    private fun compileThrow(stmt: net.sergeych.lyng.ThrowStatement): CompiledValue? {
        val value = compileStatementValueOrFallback(stmt.throwExpr) ?: return null
        val objValue = ensureObjSlot(value)
        val posId = builder.addConst(BytecodeConst.PosVal(stmt.pos))
        builder.emit(Opcode.THROW, posId, objValue.slot)
        return objValue
    }

    private fun emitExtensionPropertyDecl(
        stmt: net.sergeych.lyng.ExtensionPropertyDeclStatement
    ): CompiledValue {
        val constId = builder.addConst(
            BytecodeConst.ExtensionPropertyDecl(
                stmt.extTypeName,
                stmt.property,
                stmt.visibility,
                stmt.setterVisibility
            )
        )
        val slot = allocSlot()
        builder.emit(Opcode.DECL_EXT_PROPERTY, constId, slot)
        updateSlotType(slot, SlotType.OBJ)
        return CompiledValue(slot, SlotType.OBJ)
    }

    private fun resetAddrCache() {
        addrSlotByScopeSlot.clear()
    }

    private fun ensureScopeAddr(scopeSlot: Int): Int {
        val existing = addrSlotByScopeSlot[scopeSlot]
        val addrSlot = existing ?: run {
            val created = nextAddrSlot++
            addrSlotByScopeSlot[scopeSlot] = created
            created
        }
        builder.emit(Opcode.RESOLVE_SCOPE_SLOT, scopeSlot, addrSlot)
        return addrSlot
    }

    private fun emitLoadFromAddr(addrSlot: Int, dstSlot: Int, type: SlotType) {
        when (type) {
            SlotType.INT -> builder.emit(Opcode.LOAD_INT_ADDR, addrSlot, dstSlot)
            SlotType.REAL -> builder.emit(Opcode.LOAD_REAL_ADDR, addrSlot, dstSlot)
            SlotType.BOOL -> builder.emit(Opcode.LOAD_BOOL_ADDR, addrSlot, dstSlot)
            SlotType.OBJ -> builder.emit(Opcode.LOAD_OBJ_ADDR, addrSlot, dstSlot)
            else -> builder.emit(Opcode.LOAD_OBJ_ADDR, addrSlot, dstSlot)
        }
    }

    private fun emitStoreToAddr(srcSlot: Int, addrSlot: Int, type: SlotType) {
        when (type) {
            SlotType.INT -> builder.emit(Opcode.STORE_INT_ADDR, srcSlot, addrSlot)
            SlotType.REAL -> builder.emit(Opcode.STORE_REAL_ADDR, srcSlot, addrSlot)
            SlotType.BOOL -> builder.emit(Opcode.STORE_BOOL_ADDR, srcSlot, addrSlot)
            SlotType.OBJ -> builder.emit(Opcode.STORE_OBJ_ADDR, srcSlot, addrSlot)
            else -> builder.emit(Opcode.STORE_OBJ_ADDR, srcSlot, addrSlot)
        }
    }

    private fun emitMove(value: CompiledValue, dstSlot: Int) {
        val srcSlot = value.slot
        val srcIsScope = srcSlot < scopeSlotCount
        val dstIsScope = dstSlot < scopeSlotCount
        if (value.type != SlotType.UNKNOWN) {
            if (srcIsScope && !dstIsScope) {
                val addrSlot = ensureScopeAddr(srcSlot)
                emitLoadFromAddr(addrSlot, dstSlot, value.type)
                propagateObjClass(value.type, srcSlot, dstSlot)
                return
            }
            if (dstIsScope) {
                val addrSlot = ensureScopeAddr(dstSlot)
                emitStoreToAddr(srcSlot, addrSlot, value.type)
                propagateObjClass(value.type, srcSlot, dstSlot)
                return
            }
        }
        when (value.type) {
            SlotType.INT -> builder.emit(Opcode.MOVE_INT, srcSlot, dstSlot)
            SlotType.REAL -> builder.emit(Opcode.MOVE_REAL, srcSlot, dstSlot)
            SlotType.BOOL -> builder.emit(Opcode.MOVE_BOOL, srcSlot, dstSlot)
            SlotType.OBJ -> builder.emit(Opcode.MOVE_OBJ, srcSlot, dstSlot)
            else -> builder.emit(Opcode.BOX_OBJ, srcSlot, dstSlot)
        }
        propagateObjClass(value.type, srcSlot, dstSlot)
    }

    private fun propagateObjClass(type: SlotType, srcSlot: Int, dstSlot: Int) {
        if (type == SlotType.OBJ || type == SlotType.UNKNOWN) {
            val cls = slotObjClass[srcSlot]
            if (cls != null) {
                slotObjClass[dstSlot] = cls
            } else {
                slotObjClass.remove(dstSlot)
            }
        } else {
            slotObjClass.remove(dstSlot)
        }
    }

    private fun setPos(pos: Pos?) {
        currentPos = pos
        builder.setPos(pos)
    }

    private fun callSitePos(): Pos = currentPos ?: Pos.builtIn

    private fun refPosOrCurrent(ref: ObjRef): Pos {
        val refPos = when (ref) {
            is LocalVarRef -> ref.pos()
            is LocalSlotRef -> ref.pos()
            is QualifiedThisRef -> ref.pos()
            is ImplicitThisMethodCallRef -> ref.pos()
            is StatementRef -> ref.statement.pos
            else -> null
        }
        return refPos ?: callSitePos()
    }

    private fun compileRefWithFallback(ref: ObjRef, forceType: SlotType?, pos: Pos): CompiledValue? {
        setPos(pos)
        var compiled = compileRef(ref)
        if (compiled != null) {
            if (forceType == null) return compiled
            if (compiled.type == forceType) return compiled
            if (forceType == SlotType.BOOL) {
                val converted = when (compiled.type) {
                    SlotType.INT -> {
                        val dst = allocSlot()
                        builder.emit(Opcode.INT_TO_BOOL, compiled.slot, dst)
                        updateSlotType(dst, SlotType.BOOL)
                        CompiledValue(dst, SlotType.BOOL)
                    }
                    SlotType.OBJ -> {
                        val dst = allocSlot()
                        builder.emit(Opcode.OBJ_TO_BOOL, compiled.slot, dst)
                        updateSlotType(dst, SlotType.BOOL)
                        CompiledValue(dst, SlotType.BOOL)
                    }
                    else -> null
                }
                if (converted != null) return converted
            }
            if (compiled.type == SlotType.UNKNOWN) {
                compiled = null
            }
        }
        if (ref is LocalVarRef || ref is LocalSlotRef) {
            val name = when (ref) {
                is LocalVarRef -> ref.name
                is LocalSlotRef -> ref.name
                else -> "unknown"
            }
            val refKind = ref::class.simpleName ?: "LocalRef"
            val loopKeys = loopSlotOverrides.keys.sorted().joinToString(prefix = "[", postfix = "]")
            val localKeys = localSlotIndexByName.keys.sorted().joinToString(prefix = "[", postfix = "]")
            val scopeKeys = scopeSlotIndexByName.keys.sorted().joinToString(prefix = "[", postfix = "]")
            val info = " ref=$refKind loopSlots=$loopKeys localSlots=$localKeys scopeSlots=$scopeKeys forceScopeSlots=$forceScopeSlots"
            throw BytecodeCompileException("Unresolved name '$name'.$info", pos)
        }
        val refInfo = when (ref) {
            is FieldRef -> "FieldRef(${ref.name})"
            else -> ref::class.simpleName ?: "UnknownRef"
        }
        val extra = if (ref is LocalVarRef) {
            val names = scopeSlotNameMap.values.joinToString(prefix = "[", postfix = "]")
            " scopeSlots=$names"
        } else {
            ""
        }
        throw BytecodeCompileException(
            "Bytecode compile error: unsupported expression ($refInfo)$extra",
            pos
        )
    }

    private fun resolveReceiverClass(ref: ObjRef): ObjClass? {
        return when (ref) {
            is LocalSlotRef -> {
                val slot = resolveSlot(ref)
                val fromSlot = slot?.let { slotObjClass[it] }
                fromSlot
                    ?: slotTypeByScopeId[refScopeId(ref)]?.get(refSlot(ref))
                    ?: nameObjClass[ref.name]
                    ?: resolveTypeNameClass(ref.name)
                    ?: slotInitClassByKey[ScopeSlotKey(refScopeId(ref), refSlot(ref))]
                    ?: run {
                        val match = slotInitClassByKey.entries.firstOrNull { (key, _) ->
                            val name = localSlotInfoMap[key]?.name ?: scopeSlotNameMap[key]
                            name == ref.name
                        }
                        match?.value
                    }
            }
            is LocalVarRef -> {
                val fromSlot = resolveDirectNameSlot(ref.name)?.let { slotObjClass[it.slot] }
                if (fromSlot != null) return fromSlot
                val key = localSlotInfoMap.entries.firstOrNull { it.value.name == ref.name }?.key
                key?.let {
                    slotTypeByScopeId[it.scopeId]?.get(it.slot)
                        ?: slotInitClassByKey[it]
                } ?: nameObjClass[ref.name]
                    ?: resolveTypeNameClass(ref.name)
            }
            is ListLiteralRef -> ObjList.type
            is MapLiteralRef -> ObjMap.type
            is RangeRef -> ObjRange.type
            is StatementRef -> (ref.statement as? ExpressionStatement)?.let { resolveReceiverClass(it.ref) }
            is ConstRef -> when (ref.constValue) {
                is ObjList -> ObjList.type
                is ObjMap -> ObjMap.type
                is ObjRange -> ObjRange.type
                is ObjString -> ObjString.type
                is ObjRegex -> ObjRegex.type
                is ObjInt -> ObjInt.type
                is ObjReal -> ObjReal.type
                is ObjBool -> ObjBool.type
                is ObjChar -> ObjChar.type
                else -> null
            }
            is CastRef -> resolveTypeRefClass(ref.castTypeRef())
                ?: resolveReceiverClass(ref.castValueRef())
            is FieldRef -> {
                val targetClass = resolveReceiverClass(ref.target) ?: return null
                inferFieldReturnClass(targetClass, ref.name)
            }
            is MethodCallRef -> {
                val targetClass = resolveReceiverClass(ref.receiver) ?: return null
                if (targetClass == ObjString.type && ref.name == "re" && ref.args.isEmpty() && !ref.isOptional) {
                    ObjRegex.type
                } else {
                    inferMethodCallReturnClass(ref.name)
                }
            }
            is CallRef -> inferCallReturnClass(ref)
            else -> null
        }
    }

    private fun isAllowedObjectMember(memberName: String): Boolean {
        return when (memberName) {
            "toString",
            "toInspectString",
            "let",
            "also",
            "apply",
            "run" -> true
            else -> false
        }
    }

    private fun refSlot(ref: LocalSlotRef): Int = ref.slot
    private fun refScopeId(ref: LocalSlotRef): Int = ref.scopeId
    private fun binaryLeft(ref: BinaryOpRef): ObjRef = ref.left
    private fun binaryRight(ref: BinaryOpRef): ObjRef = ref.right
    private fun binaryOp(ref: BinaryOpRef): BinOp = ref.op

    private fun resolveReceiverClassForScopeCollection(ref: ObjRef): ObjClass? {
        return when (ref) {
            is LocalSlotRef -> nameObjClass[ref.name] ?: resolveTypeNameClass(ref.name)
            is LocalVarRef -> nameObjClass[ref.name] ?: resolveTypeNameClass(ref.name)
            is ListLiteralRef -> ObjList.type
            is MapLiteralRef -> ObjMap.type
            is RangeRef -> ObjRange.type
            is StatementRef -> (ref.statement as? ExpressionStatement)?.let { resolveReceiverClassForScopeCollection(it.ref) }
            is ConstRef -> when (ref.constValue) {
                is ObjList -> ObjList.type
                is ObjMap -> ObjMap.type
                is ObjRange -> ObjRange.type
                is ObjString -> ObjString.type
                is ObjRegex -> ObjRegex.type
                is ObjInt -> ObjInt.type
                is ObjReal -> ObjReal.type
                is ObjBool -> ObjBool.type
                is ObjChar -> ObjChar.type
                else -> null
            }
            is CastRef -> resolveTypeRefClass(ref.castTypeRef())
                ?: resolveReceiverClassForScopeCollection(ref.castValueRef())
            is FieldRef -> {
                val targetClass = resolveReceiverClassForScopeCollection(ref.target) ?: return null
                inferFieldReturnClass(targetClass, ref.name)
            }
            is MethodCallRef -> {
                val targetClass = resolveReceiverClassForScopeCollection(ref.receiver) ?: return null
                if (targetClass == ObjString.type && ref.name == "re" && ref.args.isEmpty() && !ref.isOptional) {
                    ObjRegex.type
                } else {
                    inferMethodCallReturnClass(ref.name)
                }
            }
            is CallRef -> inferCallReturnClass(ref)
            else -> null
        }
    }

    private fun resolveTypeRefClass(ref: ObjRef): ObjClass? {
        return when (ref) {
            is ConstRef -> ref.constValue as? ObjClass
            is LocalSlotRef -> resolveTypeNameClass(ref.name) ?: nameObjClass[ref.name]
            is LocalVarRef -> resolveTypeNameClass(ref.name) ?: nameObjClass[ref.name]
            else -> null
        }
    }

    private fun resolveTypeNameClass(name: String): ObjClass? {
        val shortName = name.substringAfterLast('.')
        return when (shortName) {
            "Object", "Obj" -> Obj.rootObjectType
            "String" -> ObjString.type
            "Int" -> ObjInt.type
            "Real" -> ObjReal.type
            "Bool" -> ObjBool.type
            "Char" -> ObjChar.type
            "List" -> ObjList.type
            "Map" -> ObjMap.type
            "Set" -> ObjSet.type
            "Range", "IntRange" -> ObjRange.type
            "Iterator" -> ObjIterator
            "Iterable" -> ObjIterable
            "Collection" -> ObjCollection
            "Array" -> ObjArray
            "Deferred" -> ObjDeferred.type
            "CompletableDeferred" -> ObjCompletableDeferred.type
            "Mutex" -> ObjMutex.type
            "Flow" -> ObjFlow.type
            "FlowBuilder" -> ObjFlowBuilder.type
            "Regex" -> ObjRegex.type
            "RegexMatch" -> ObjRegexMatch.type
            "MapEntry" -> ObjMapEntry.type
            "Instant" -> ObjInstant.type
            "DateTime" -> ObjDateTime.type
            "Duration" -> ObjDuration.type
            "Exception" -> ObjException.Root
            "Class" -> ObjClassType
            "Callable" -> Statement.type
            else -> null
        }
    }

    private fun inferCallReturnClass(ref: CallRef): ObjClass? {
        return when (val target = ref.target) {
            is LocalSlotRef -> nameObjClass[target.name] ?: resolveTypeNameClass(target.name)
            is LocalVarRef -> nameObjClass[target.name] ?: resolveTypeNameClass(target.name)
            is ConstRef -> target.constValue as? ObjClass
            else -> null
        }
    }

    private fun inferMethodCallReturnClass(name: String): ObjClass? = when (name) {
        "map",
        "mapNotNull",
        "filter",
        "filterNotNull",
        "drop",
        "take",
        "flatMap",
        "flatten",
        "sorted",
        "sortedBy",
        "sortedWith",
        "reversed",
        "toList",
        "shuffle",
        "shuffled" -> ObjList.type
        "dropLast" -> ObjFlow.type
        "takeLast" -> ObjRingBuffer.type
        "iterator" -> ObjIterator
        "count" -> ObjInt.type
        "toSet" -> ObjSet.type
        "toMap" -> ObjMap.type
        "joinToString" -> ObjString.type
        "now",
        "truncateToSecond",
        "truncateToMinute",
        "truncateToMillisecond" -> ObjInstant.type
        "toDateTime",
        "toTimeZone",
        "toUTC",
        "parseRFC3339",
        "addYears",
        "addMonths",
        "addDays",
        "addHours",
        "addMinutes",
        "addSeconds" -> ObjDateTime.type
        "toInstant" -> ObjInstant.type
        "toRFC3339",
        "toSortableString",
        "toJsonString",
        "decodeUtf8",
        "toDump",
        "toString" -> ObjString.type
        "startsWith",
        "matches" -> ObjBool.type
        "toInt",
        "toEpochSeconds" -> ObjInt.type
        "toMutable" -> ObjMutableBuffer.type
        "seq" -> ObjFlow.type
        "encode" -> ObjBitBuffer.type
        "assertThrows" -> ObjException.Root
        else -> null
    }

    private fun inferFieldReturnClass(targetClass: ObjClass?, name: String): ObjClass? {
        if (targetClass == null) return null
        if (targetClass == ObjDynamic.type) return ObjDynamic.type
        if (targetClass == ObjInstant.type && (name == "distantFuture" || name == "distantPast")) {
            return ObjInstant.type
        }
        if (targetClass == ObjString.type && name == "re") {
            return ObjRegex.type
        }
        if (targetClass == ObjInt.type || targetClass == ObjReal.type) {
            return when (name) {
                "day",
                "days",
                "hour",
                "hours",
                "minute",
                "minutes",
                "second",
                "seconds",
                "millisecond",
                "milliseconds",
                "microsecond",
                "microseconds" -> ObjDuration.type
                else -> null
            }
        }
        if (targetClass == ObjDuration.type) {
            return when (name) {
                "days",
                "hours",
                "minutes",
                "seconds",
                "milliseconds",
                "microseconds" -> ObjReal.type
                else -> null
            }
        }
        if (targetClass == ObjInstant.type) {
            return when (name) {
                "epochSeconds",
                "epochWholeSeconds" -> ObjInt.type
                "truncateToSecond",
                "truncateToMinute",
                "truncateToMillisecond" -> ObjInstant.type
                else -> null
            }
        }
        if (targetClass == ObjDateTime.type) {
            return when (name) {
                "year",
                "month",
                "day",
                "hour",
                "minute",
                "second",
                "dayOfWeek",
                "nanosecond" -> ObjInt.type
                "timeZone" -> ObjString.type
                else -> null
            }
        }
        if (targetClass == ObjException.Root || targetClass.allParentsSet.contains(ObjException.Root)) {
            return when (name) {
                "message" -> ObjString.type
                "stackTrace" -> ObjList.type
                else -> null
            }
        }
        if (targetClass == ObjRegex.type && name == "pattern") {
            return ObjString.type
        }
        return null
    }

    private fun queueExtensionCallableNames(receiverClass: ObjClass, memberName: String) {
        for (cls in receiverClass.mro) {
            val name = extensionCallableName(cls.className, memberName)
            if (allowedScopeNames == null || allowedScopeNames.contains(name)) {
                pendingScopeNameRefs.add(name)
            }
        }
    }

    private fun queueExtensionPropertyNames(receiverClass: ObjClass, memberName: String) {
        for (cls in receiverClass.mro) {
            val getter = extensionPropertyGetterName(cls.className, memberName)
            if (allowedScopeNames == null || allowedScopeNames.contains(getter)) {
                pendingScopeNameRefs.add(getter)
            }
            val setter = extensionPropertySetterName(cls.className, memberName)
            if (allowedScopeNames == null || allowedScopeNames.contains(setter)) {
                pendingScopeNameRefs.add(setter)
            }
        }
    }
    private fun unaryOperand(ref: UnaryOpRef): ObjRef = ref.a
    private fun unaryOp(ref: UnaryOpRef): UnaryOp = ref.op
    private fun assignTarget(ref: AssignRef): LocalSlotRef? = ref.target as? LocalSlotRef
    private fun assignValue(ref: AssignRef): ObjRef = ref.value
    private fun refPos(ref: BinaryOpRef): Pos = Pos.builtIn

    private fun resolveSlot(ref: LocalSlotRef): Int? {
        loopSlotOverrides[ref.name]?.let { return it }
        val scopeId = refScopeId(ref)
        if (isModuleSlot(scopeId, ref.name)) {
            val key = ScopeSlotKey(scopeId, refSlot(ref))
            scopeSlotMap[key]?.let { return it }
            scopeSlotIndexByName[ref.name]?.let { return it }
        }
        if (ref.captureOwnerScopeId != null) {
            val ownerKey = ScopeSlotKey(ref.captureOwnerScopeId, ref.captureOwnerSlot ?: refSlot(ref))
            val ownerLocal = localSlotIndexByKey[ownerKey]
            if (ownerLocal != null) {
                return scopeSlotCount + ownerLocal
            }
            val nameLocal = localSlotIndexByName[ref.name]
            if (nameLocal != null) {
                return scopeSlotCount + nameLocal
            }
            val scopeKey = ScopeSlotKey(refScopeId(ref), refSlot(ref))
            return scopeSlotMap[scopeKey]
        }
        if (forceScopeSlots) {
            val scopeKey = ScopeSlotKey(refScopeId(ref), refSlot(ref))
            return scopeSlotMap[scopeKey]
        }
        val localKey = ScopeSlotKey(refScopeId(ref), refSlot(ref))
        val localIndex = localSlotIndexByKey[localKey]
        if (localIndex != null) return scopeSlotCount + localIndex
        val nameIndex = localSlotIndexByName[ref.name]
        if (nameIndex != null) return scopeSlotCount + nameIndex
        val scopeKey = ScopeSlotKey(refScopeId(ref), refSlot(ref))
        return scopeSlotMap[scopeKey]
    }

    private fun updateSlotType(slot: Int, type: SlotType) {
        if (type == SlotType.UNKNOWN) {
            slotTypes.remove(slot)
        } else {
            slotTypes[slot] = type
        }
    }

    private fun prepareCompilation(stmt: Statement) {
        builder = CmdBuilder()
        nextSlot = 0
        nextAddrSlot = 0
        slotTypes.clear()
        slotObjClass.clear()
        nameObjClass.clear()
        if (knownNameObjClass.isNotEmpty()) {
            nameObjClass.putAll(knownNameObjClass)
        }
        slotInitClassByKey.clear()
        scopeSlotMap.clear()
        scopeSlotNameMap.clear()
        scopeSlotMutableMap.clear()
        localSlotInfoMap.clear()
        localSlotIndexByKey.clear()
        localSlotIndexByName.clear()
        loopSlotOverrides.clear()
        scopeSlotIndexByName.clear()
        pendingScopeNameRefs.clear()
        localSlotNames = emptyArray()
        localSlotMutables = BooleanArray(0)
        declaredLocalKeys.clear()
        localRangeRefs.clear()
        intLoopVarNames.clear()
        addrSlotByScopeSlot.clear()
        loopStack.clear()
        forceScopeSlots = allowLocalSlots && containsValueFnRef(stmt)
        if (slotTypeByScopeId.isNotEmpty()) {
            for ((scopeId, slots) in slotTypeByScopeId) {
                for ((slotIndex, cls) in slots) {
                    slotInitClassByKey[ScopeSlotKey(scopeId, slotIndex)] = cls
                }
            }
        }
        if (allowLocalSlots) {
            collectLoopVarNames(stmt)
        }
        collectScopeSlots(stmt)
        if (allowLocalSlots) {
            collectLoopSlotPlans(stmt, 0)
        }
        if (pendingScopeNameRefs.isNotEmpty()) {
            val existingNames = HashSet<String>(scopeSlotNameMap.values)
            var maxSlotIndex = scopeSlotMap.keys.maxOfOrNull { it.slot } ?: -1
            for (name in pendingScopeNameRefs) {
                if (!existingNames.add(name)) continue
                maxSlotIndex += 1
                val key = ScopeSlotKey(0, maxSlotIndex)
                scopeSlotMap[key] = scopeSlotMap.size
                scopeSlotNameMap[key] = name
            }
        }
        scopeSlotCount = scopeSlotMap.size
        scopeSlotIndices = IntArray(scopeSlotCount)
        scopeSlotNames = arrayOfNulls(scopeSlotCount)
        scopeSlotIsModule = BooleanArray(scopeSlotCount)
        scopeSlotMutables = BooleanArray(scopeSlotCount) { true }
        for ((key, index) in scopeSlotMap) {
            val name = scopeSlotNameMap[key]
            scopeSlotIndices[index] = key.slot
            scopeSlotNames[index] = name
            scopeSlotIsModule[index] = key.scopeId == (moduleScopeId ?: 0)
            scopeSlotMutableMap[key]?.let { scopeSlotMutables[index] = it }
        }
        if (allowLocalSlots && localSlotInfoMap.isNotEmpty()) {
            val names = ArrayList<String?>(localSlotInfoMap.size)
            val mutables = BooleanArray(localSlotInfoMap.size)
            var index = 0
            for ((key, info) in localSlotInfoMap) {
                localSlotIndexByKey[key] = index
                if (!localSlotIndexByName.containsKey(info.name)) {
                    localSlotIndexByName[info.name] = index
                }
                names.add(info.name)
                mutables[index] = info.isMutable
                index += 1
            }
            localSlotNames = names.toTypedArray()
            localSlotMutables = mutables
        }
        if (scopeSlotCount > 0) {
            for ((key, index) in scopeSlotMap) {
                val name = scopeSlotNameMap[key] ?: continue
                if (!scopeSlotIndexByName.containsKey(name)) {
                    scopeSlotIndexByName[name] = index
                }
            }
        }
        if (slotInitClassByKey.isNotEmpty()) {
            for ((key, cls) in slotInitClassByKey) {
                val name = localSlotInfoMap[key]?.name ?: scopeSlotNameMap[key]
                if (name != null) {
                    nameObjClass[name] = cls
                }
                val localIndex = localSlotIndexByKey[key]
                val slot = when {
                    localIndex != null -> scopeSlotCount + localIndex
                    else -> scopeSlotMap[key]
                }
                if (slot != null) {
                    slotObjClass[slot] = cls
                }
            }
        }
        nextSlot = scopeSlotCount + localSlotNames.size
    }

    private fun collectScopeSlots(stmt: Statement) {
        if (stmt is BytecodeStatement) {
            collectScopeSlots(stmt.original)
            return
        }
        when (stmt) {
            is ExpressionStatement -> collectScopeSlotsRef(stmt.ref)
            is BlockStatement -> {
                for (child in stmt.statements()) {
                    collectScopeSlots(child)
                }
            }
            is net.sergeych.lyng.InlineBlockStatement -> {
                for (child in stmt.statements()) {
                    collectScopeSlots(child)
                }
            }
            is VarDeclStatement -> {
                val slotIndex = stmt.slotIndex
                val scopeId = stmt.scopeId ?: 0
                val cls = stmt.initializerObjClass ?: objClassForInitializer(stmt.initializer)
                if (cls != null) {
                    nameObjClass[stmt.name] = cls
                    if (slotIndex != null) {
                        slotInitClassByKey[ScopeSlotKey(scopeId, slotIndex)] = cls
                    }
                }
                val isModuleSlot = isModuleSlot(scopeId, stmt.name)
                if (allowLocalSlots && !forceScopeSlots && slotIndex != null && !isModuleSlot) {
                    val key = ScopeSlotKey(scopeId, slotIndex)
                    declaredLocalKeys.add(key)
                    if (!localSlotInfoMap.containsKey(key)) {
                        localSlotInfoMap[key] = LocalSlotInfo(stmt.name, stmt.isMutable)
                    }
                    if (!stmt.isMutable) {
                        extractDeclaredRange(stmt.initializer)?.let { range ->
                            localRangeRefs[key] = range
                        }
                    }
                } else if (slotIndex != null) {
                    val key = ScopeSlotKey(scopeId, slotIndex)
                    if (!scopeSlotMap.containsKey(key)) {
                        scopeSlotMap[key] = scopeSlotMap.size
                    }
                    if (!scopeSlotNameMap.containsKey(key)) {
                        scopeSlotNameMap[key] = stmt.name
                    }
                }
                stmt.initializer?.let { collectScopeSlots(it) }
            }
            is IfStatement -> {
                collectScopeSlots(stmt.condition)
                collectScopeSlots(stmt.ifBody)
                stmt.elseBody?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ForInStatement -> {
                collectScopeSlots(stmt.source)
                collectScopeSlots(stmt.body)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.WhileStatement -> {
                collectScopeSlots(stmt.condition)
                collectScopeSlots(stmt.body)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                collectScopeSlots(stmt.body)
                collectScopeSlots(stmt.condition)
                stmt.elseStatement?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.WhenStatement -> {
                collectScopeSlots(stmt.value)
                for (case in stmt.cases) {
                    for (cond in case.conditions) {
                        collectScopeSlots(cond.expr)
                    }
                    collectScopeSlots(case.block)
                }
                stmt.elseCase?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.TryStatement -> {
                collectScopeSlots(stmt.body)
                for (catchBlock in stmt.catches) {
                    collectScopeSlots(catchBlock.block)
                }
                stmt.finallyClause?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectScopeSlots(it) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectScopeSlots(stmt.throwExpr)
            }
            else -> {}
        }
    }

    private fun collectLoopSlotPlans(stmt: Statement, scopeDepth: Int) {
        if (stmt is BytecodeStatement) {
            collectLoopSlotPlans(stmt.original, scopeDepth)
            return
        }
        if (forceScopeSlots) {
            when (stmt) {
                is net.sergeych.lyng.ForInStatement -> {
                    collectLoopSlotPlans(stmt.source, scopeDepth)
                    val loopDepth = scopeDepth + 1
                    collectLoopSlotPlans(stmt.body, loopDepth)
                    stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
                }
                is net.sergeych.lyng.WhileStatement -> {
                    collectLoopSlotPlans(stmt.condition, scopeDepth)
                    val loopDepth = scopeDepth + 1
                    collectLoopSlotPlans(stmt.body, loopDepth)
                    stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
                }
                is net.sergeych.lyng.DoWhileStatement -> {
                    val loopDepth = scopeDepth + 1
                    collectLoopSlotPlans(stmt.body, loopDepth)
                    collectLoopSlotPlans(stmt.condition, loopDepth)
                    stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
                }
                is BlockStatement -> {
                    val nextDepth = scopeDepth + 1
                    for (child in stmt.statements()) {
                        collectLoopSlotPlans(child, nextDepth)
                    }
                }
                is net.sergeych.lyng.InlineBlockStatement -> {
                    for (child in stmt.statements()) {
                        collectLoopSlotPlans(child, scopeDepth)
                    }
                }
                is IfStatement -> {
                    collectLoopSlotPlans(stmt.condition, scopeDepth)
                    collectLoopSlotPlans(stmt.ifBody, scopeDepth)
                    stmt.elseBody?.let { collectLoopSlotPlans(it, scopeDepth) }
                }
                is VarDeclStatement -> {
                    stmt.initializer?.let { collectLoopSlotPlans(it, scopeDepth) }
                }
                is ExpressionStatement -> {}
                is net.sergeych.lyng.ReturnStatement -> {
                    stmt.resultExpr?.let { collectLoopSlotPlans(it, scopeDepth) }
                }
                is net.sergeych.lyng.ThrowStatement -> {
                    collectLoopSlotPlans(stmt.throwExpr, scopeDepth)
                }
                else -> {}
            }
            return
        }
        when (stmt) {
            is net.sergeych.lyng.ForInStatement -> {
                collectLoopSlotPlans(stmt.source, scopeDepth)
                val loopDepth = scopeDepth + 1
                collectLoopSlotPlans(stmt.body, loopDepth)
                stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
            }
            is net.sergeych.lyng.WhileStatement -> {
                collectLoopSlotPlans(stmt.condition, scopeDepth)
                val loopDepth = scopeDepth + 1
                collectLoopSlotPlans(stmt.body, loopDepth)
                stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                val loopDepth = scopeDepth + 1
                collectLoopSlotPlans(stmt.body, loopDepth)
                collectLoopSlotPlans(stmt.condition, loopDepth)
                stmt.elseStatement?.let { collectLoopSlotPlans(it, loopDepth) }
            }
            is BlockStatement -> {
                val nextDepth = scopeDepth + 1
                for (child in stmt.statements()) {
                    collectLoopSlotPlans(child, nextDepth)
                }
            }
            is net.sergeych.lyng.InlineBlockStatement -> {
                for (child in stmt.statements()) {
                    collectLoopSlotPlans(child, scopeDepth)
                }
            }
            is IfStatement -> {
                collectLoopSlotPlans(stmt.condition, scopeDepth)
                collectLoopSlotPlans(stmt.ifBody, scopeDepth)
                stmt.elseBody?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is VarDeclStatement -> {
                stmt.initializer?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is ExpressionStatement -> {
                // no-op
            }
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectLoopSlotPlans(it, scopeDepth) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectLoopSlotPlans(stmt.throwExpr, scopeDepth)
            }
            else -> {}
        }
    }

    private fun isModuleSlot(scopeId: Int, name: String?): Boolean {
        val moduleId = moduleScopeId ?: 0
        if (scopeId != moduleId) return false
        if (allowedScopeNames == null || name == null) return true
        return allowedScopeNames.contains(name)
    }

    private fun collectLoopVarNames(stmt: Statement) {
        if (stmt is BytecodeStatement) {
            collectLoopVarNames(stmt.original)
            return
        }
        when (stmt) {
            is net.sergeych.lyng.ForInStatement -> {
                if (stmt.constRange != null) {
                    intLoopVarNames.add(stmt.loopVarName)
                }
                collectLoopVarNames(stmt.source)
                collectLoopVarNames(stmt.body)
                stmt.elseStatement?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.WhileStatement -> {
                collectLoopVarNames(stmt.condition)
                collectLoopVarNames(stmt.body)
                stmt.elseStatement?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                collectLoopVarNames(stmt.body)
                collectLoopVarNames(stmt.condition)
                stmt.elseStatement?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.InlineBlockStatement -> {
                for (child in stmt.statements()) {
                    collectLoopVarNames(child)
                }
            }
            is BlockStatement -> {
                for (child in stmt.statements()) {
                    collectLoopVarNames(child)
                }
            }
            is VarDeclStatement -> {
                stmt.initializer?.let { collectLoopVarNames(it) }
            }
            is IfStatement -> {
                collectLoopVarNames(stmt.condition)
                collectLoopVarNames(stmt.ifBody)
                stmt.elseBody?.let { collectLoopVarNames(it) }
            }
            is ExpressionStatement -> collectLoopVarNamesRef(stmt.ref)
            is net.sergeych.lyng.BreakStatement -> {
                stmt.resultExpr?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { collectLoopVarNames(it) }
            }
            is net.sergeych.lyng.ThrowStatement -> {
                collectLoopVarNames(stmt.throwExpr)
            }
            is net.sergeych.lyng.TryStatement -> {
                collectLoopVarNames(stmt.body)
                for (catchBlock in stmt.catches) {
                    collectLoopVarNames(catchBlock.block)
                }
                stmt.finallyClause?.let { collectLoopVarNames(it) }
            }
            else -> {}
        }
    }

    private fun collectLoopVarNamesRef(ref: ObjRef) {
        when (ref) {
            is BinaryOpRef -> {
                collectLoopVarNamesRef(binaryLeft(ref))
                collectLoopVarNamesRef(binaryRight(ref))
            }
            is UnaryOpRef -> collectLoopVarNamesRef(unaryOperand(ref))
            is AssignRef -> collectLoopVarNamesRef(assignValue(ref))
            is AssignOpRef -> {
                collectLoopVarNamesRef(ref.target)
                collectLoopVarNamesRef(ref.value)
            }
            is IncDecRef -> collectLoopVarNamesRef(ref.target)
            is ConditionalRef -> {
                collectLoopVarNamesRef(ref.condition)
                collectLoopVarNamesRef(ref.ifTrue)
                collectLoopVarNamesRef(ref.ifFalse)
            }
            is ElvisRef -> {
                collectLoopVarNamesRef(ref.left)
                collectLoopVarNamesRef(ref.right)
            }
            is FieldRef -> collectLoopVarNamesRef(ref.target)
            is IndexRef -> {
                collectLoopVarNamesRef(ref.targetRef)
                collectLoopVarNamesRef(ref.indexRef)
            }
            else -> {}
        }
    }

    private fun collectScopeSlotsRef(ref: ObjRef) {
        when (ref) {
            is LocalSlotRef -> {
                val scopeId = refScopeId(ref)
                val key = ScopeSlotKey(scopeId, refSlot(ref))
                if (ref.captureOwnerScopeId != null) {
                    if (!scopeSlotMap.containsKey(key)) {
                        scopeSlotMap[key] = scopeSlotMap.size
                    }
                    if (!scopeSlotNameMap.containsKey(key)) {
                        scopeSlotNameMap[key] = ref.name
                    }
                    return
                }
                val shouldLocalize = !forceScopeSlots || intLoopVarNames.contains(ref.name)
                val isModuleSlot = isModuleSlot(scopeId, ref.name)
                if (allowLocalSlots && !ref.isDelegated && shouldLocalize && !isModuleSlot) {
                    if (!localSlotInfoMap.containsKey(key)) {
                        localSlotInfoMap[key] = LocalSlotInfo(ref.name, ref.isMutable)
                    }
                    return
                }
                if (!scopeSlotMap.containsKey(key)) {
                    scopeSlotMap[key] = scopeSlotMap.size
                }
                if (!scopeSlotNameMap.containsKey(key)) {
                    scopeSlotNameMap[key] = ref.name
                }
                if (!scopeSlotMutableMap.containsKey(key)) {
                    scopeSlotMutableMap[key] = ref.isMutable
                }
            }
            is LocalVarRef -> {}
            is BinaryOpRef -> {
                collectScopeSlotsRef(binaryLeft(ref))
                collectScopeSlotsRef(binaryRight(ref))
            }
            is UnaryOpRef -> collectScopeSlotsRef(unaryOperand(ref))
            is CastRef -> {
                collectScopeSlotsRef(ref.castValueRef())
                collectScopeSlotsRef(ref.castTypeRef())
            }
            is LogicalAndRef -> {
                collectScopeSlotsRef(ref.left())
                collectScopeSlotsRef(ref.right())
            }
            is LogicalOrRef -> {
                collectScopeSlotsRef(ref.left())
                collectScopeSlotsRef(ref.right())
            }
            is AssignRef -> {
                val target = assignTarget(ref)
                if (target != null) {
                    val scopeId = refScopeId(target)
                    val key = ScopeSlotKey(scopeId, refSlot(target))
                    if (target.captureOwnerScopeId != null) {
                        if (!scopeSlotMap.containsKey(key)) {
                            scopeSlotMap[key] = scopeSlotMap.size
                        }
                        if (!scopeSlotNameMap.containsKey(key)) {
                            scopeSlotNameMap[key] = target.name
                        }
                    } else {
                        val shouldLocalize = !forceScopeSlots || intLoopVarNames.contains(target.name)
                        val isModuleSlot = isModuleSlot(scopeId, target.name)
                        if (allowLocalSlots && !target.isDelegated && shouldLocalize && !isModuleSlot) {
                            if (!localSlotInfoMap.containsKey(key)) {
                                localSlotInfoMap[key] = LocalSlotInfo(target.name, target.isMutable)
                            }
                        } else {
                            if (!scopeSlotMap.containsKey(key)) {
                                scopeSlotMap[key] = scopeSlotMap.size
                            }
                            if (!scopeSlotNameMap.containsKey(key)) {
                                scopeSlotNameMap[key] = target.name
                            }
                            if (!scopeSlotMutableMap.containsKey(key)) {
                                scopeSlotMutableMap[key] = target.isMutable
                            }
                        }
                    }
                } else {
                    collectScopeSlotsRef(ref.target)
                }
                collectScopeSlotsRef(assignValue(ref))
            }
            is AssignOpRef -> {
                collectScopeSlotsRef(ref.target)
                collectScopeSlotsRef(ref.value)
            }
            is AssignIfNullRef -> {
                collectScopeSlotsRef(ref.target)
                collectScopeSlotsRef(ref.value)
            }
            is IncDecRef -> collectScopeSlotsRef(ref.target)
            is ConditionalRef -> {
                collectScopeSlotsRef(ref.condition)
                collectScopeSlotsRef(ref.ifTrue)
                collectScopeSlotsRef(ref.ifFalse)
            }
            is ElvisRef -> {
                collectScopeSlotsRef(ref.left)
                collectScopeSlotsRef(ref.right)
            }
            is FieldRef -> {
                val receiverClass = resolveReceiverClassForScopeCollection(ref.target)
                if (receiverClass != null) {
                    val fieldId = receiverClass.instanceFieldIdMap()[ref.name]
                    val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[ref.name]
                    if (fieldId == null && methodId == null) {
                        queueExtensionPropertyNames(receiverClass, ref.name)
                    }
                }
                collectScopeSlotsRef(ref.target)
            }
            is IndexRef -> {
                collectScopeSlotsRef(ref.targetRef)
                collectScopeSlotsRef(ref.indexRef)
            }
            is ListLiteralRef -> {
                for (entry in ref.entries()) {
                    when (entry) {
                        is net.sergeych.lyng.ListEntry.Element -> collectScopeSlotsRef(entry.ref)
                        is net.sergeych.lyng.ListEntry.Spread -> collectScopeSlotsRef(entry.ref)
                    }
                }
            }
            is ImplicitThisMethodCallRef -> {
                if (ref.slotId() == null) {
                    val typeName = ref.preferredThisTypeName()
                    if (typeName != null) {
                        pendingScopeNameRefs.add(extensionCallableName(typeName, ref.methodName()))
                    }
                }
                collectScopeSlotsArgs(ref.arguments())
            }
            is ImplicitThisMemberRef -> {
                if ((ref.fieldId ?: -1) < 0 && (ref.methodId ?: -1) < 0) {
                    val typeName = ref.preferredThisTypeName()
                    if (typeName != null) {
                        pendingScopeNameRefs.add(extensionPropertyGetterName(typeName, ref.name))
                    }
                }
            }
            is MapLiteralRef -> {
                for (entry in ref.entries()) {
                    when (entry) {
                        is net.sergeych.lyng.obj.MapLiteralEntry.Named -> collectScopeSlotsRef(entry.value)
                        is net.sergeych.lyng.obj.MapLiteralEntry.Spread -> collectScopeSlotsRef(entry.ref)
                    }
                }
            }
            is CallRef -> {
                collectScopeSlotsRef(ref.target)
                collectScopeSlotsArgs(ref.args)
            }
            is MethodCallRef -> {
                val receiverClass = resolveReceiverClassForScopeCollection(ref.receiver)
                if (receiverClass != null) {
                    val methodId = receiverClass.instanceMethodIdMap(includeAbstract = true)[ref.name]
                    if (methodId == null) {
                        queueExtensionCallableNames(receiverClass, ref.name)
                    }
                }
                collectScopeSlotsRef(ref.receiver)
                collectScopeSlotsArgs(ref.args)
            }
            is StatementRef -> {
                collectScopeSlots(ref.statement)
            }
            is ImplicitThisMethodCallRef -> {
                collectScopeSlotsArgs(ref.arguments())
            }
            is ThisMethodSlotCallRef -> {
                collectScopeSlotsArgs(ref.arguments())
            }
            else -> {}
        }
    }

    private fun collectScopeSlotsArgs(args: List<ParsedArgument>) {
        for (arg in args) {
            val stmt = arg.value
            if (stmt is ExpressionStatement) {
                collectScopeSlotsRef(stmt.ref)
            }
        }
    }

    private fun containsValueFnRef(stmt: Statement): Boolean {
        if (stmt is BytecodeStatement) return containsValueFnRef(stmt.original)
        return when (stmt) {
            is ExpressionStatement -> containsValueFnRef(stmt.ref)
            is BlockStatement -> stmt.statements().any { containsValueFnRef(it) }
            is VarDeclStatement -> stmt.initializer?.let { containsValueFnRef(it) } ?: false
            is DestructuringVarDeclStatement -> {
                containsValueFnRef(stmt.initializer) || containsValueFnRef(stmt.pattern)
            }
            is net.sergeych.lyng.ForInStatement -> {
                containsValueFnRef(stmt.source) ||
                    containsValueFnRef(stmt.body) ||
                    (stmt.elseStatement?.let { containsValueFnRef(it) } ?: false)
            }
            is net.sergeych.lyng.WhileStatement -> {
                containsValueFnRef(stmt.condition) ||
                    containsValueFnRef(stmt.body) ||
                    (stmt.elseStatement?.let { containsValueFnRef(it) } ?: false)
            }
            is net.sergeych.lyng.DoWhileStatement -> {
                containsValueFnRef(stmt.body) ||
                    containsValueFnRef(stmt.condition) ||
                    (stmt.elseStatement?.let { containsValueFnRef(it) } ?: false)
            }
            is IfStatement -> {
                containsValueFnRef(stmt.condition) ||
                    containsValueFnRef(stmt.ifBody) ||
                    (stmt.elseBody?.let { containsValueFnRef(it) } ?: false)
            }
            is net.sergeych.lyng.ReturnStatement -> {
                stmt.resultExpr?.let { containsValueFnRef(it) } ?: false
            }
            is net.sergeych.lyng.ThrowStatement -> containsValueFnRef(stmt.throwExpr)
            else -> false
        }
    }

    private fun containsValueFnRef(ref: ObjRef): Boolean {
        return when (ref) {
            is ValueFnRef -> true
            is BinaryOpRef -> containsValueFnRef(binaryLeft(ref)) || containsValueFnRef(binaryRight(ref))
            is UnaryOpRef -> containsValueFnRef(unaryOperand(ref))
            is CastRef -> containsValueFnRef(ref.castValueRef()) || containsValueFnRef(ref.castTypeRef())
            is AssignRef -> {
                val target = assignTarget(ref)
                (target != null && containsValueFnRef(target)) || containsValueFnRef(assignValue(ref))
            }
            is AssignOpRef -> containsValueFnRef(ref.target) || containsValueFnRef(ref.value)
            is AssignIfNullRef -> containsValueFnRef(ref.target) || containsValueFnRef(ref.value)
            is IncDecRef -> containsValueFnRef(ref.target)
            is ConditionalRef -> {
                containsValueFnRef(ref.condition) ||
                    containsValueFnRef(ref.ifTrue) ||
                    containsValueFnRef(ref.ifFalse)
            }
            is ElvisRef -> containsValueFnRef(ref.left) || containsValueFnRef(ref.right)
            is FieldRef -> containsValueFnRef(ref.target)
            is IndexRef -> containsValueFnRef(ref.targetRef) || containsValueFnRef(ref.indexRef)
            is CallRef -> ref.tailBlock || containsValueFnRef(ref.target) || ref.args.any { arg ->
                val stmt = arg.value
                stmt is ExpressionStatement && containsValueFnRef(stmt.ref)
            }
            is MethodCallRef -> ref.tailBlock || containsValueFnRef(ref.receiver) || ref.args.any { arg ->
                val stmt = arg.value
                stmt is ExpressionStatement && containsValueFnRef(stmt.ref)
            }
            is ThisMethodSlotCallRef -> ref.hasTailBlock() || ref.arguments().any { arg ->
                val stmt = arg.value
                stmt is ExpressionStatement && containsValueFnRef(stmt.ref)
            }
            is ListLiteralRef -> ref.entries().any { entry ->
                when (entry) {
                    is net.sergeych.lyng.ListEntry.Element -> containsValueFnRef(entry.ref)
                    is net.sergeych.lyng.ListEntry.Spread -> containsValueFnRef(entry.ref)
                }
            }
            is StatementRef -> containsValueFnRef(ref.statement)
            else -> false
        }
    }

    private fun extractRangeRef(source: Statement): RangeRef? {
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        val ref = expr.ref as? RangeRef ?: return null
        return if (ref.step != null) null else ref
    }

    private fun extractDeclaredRange(stmt: Statement?): RangeRef? {
        if (stmt == null) return null
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        val expr = target as? ExpressionStatement ?: return null
        val ref = expr.ref
        if (ref is RangeRef) return ref
        if (ref is ConstRef) {
            val range = ref.constValue as? ObjRange ?: return null
            if (range.step != null && !range.step.isNull) return null
            val start = range.start as? ObjInt ?: return null
            val end = range.end as? ObjInt ?: return null
            val left = ConstRef(start.asReadonly)
            val right = ConstRef(end.asReadonly)
            return RangeRef(left, right, range.isEndInclusive)
        }
        return null
    }

    private fun extractRangeFromLocal(source: Statement): RangeRef? {
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        val localRef = expr.ref as? LocalSlotRef ?: return null
        val key = ScopeSlotKey(refScopeId(localRef), refSlot(localRef))
        return localRangeRefs[key]
    }

    private fun extractTypedRangeLocal(source: Statement): LocalSlotRef? {
        if (rangeLocalNames.isEmpty()) return null
        val target = if (source is BytecodeStatement) source.original else source
        val expr = target as? ExpressionStatement ?: return null
        val localRef = expr.ref as? LocalSlotRef ?: return null
        if (localRef.isDelegated) return null
        return if (rangeLocalNames.contains(localRef.name)) localRef else null
    }

    private data class ScopeSlotKey(val scopeId: Int, val slot: Int)
}
