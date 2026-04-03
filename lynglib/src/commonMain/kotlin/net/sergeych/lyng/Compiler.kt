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

package net.sergeych.lyng

import net.sergeych.lyng.Compiler.Companion.compile
import net.sergeych.lyng.bytecode.*
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
import net.sergeych.lyng.pacman.ImportProvider
import net.sergeych.lyng.resolution.*

/**
 * The LYNG compiler.
 */
class Compiler(
    val cc: CompilerContext,
    val importManager: ImportProvider,
    @Suppress("UNUSED_PARAMETER")
    settings: Settings = Settings()
) {

    // Stack of parameter-to-slot plans for current function being parsed (by declaration index)
    @Suppress("unused")
    private val paramSlotPlanStack = mutableListOf<Map<String, Int>>()
//    private val currentParamSlotPlan: Map<String, Int>?
//        get() = paramSlotPlanStack.lastOrNull()

    // Track identifiers known to be locals/parameters in the current function for fast local emission
    private val localNamesStack = mutableListOf<MutableSet<String>>()
    private val localShadowedNamesStack = mutableListOf<MutableSet<String>>()
    private val currentLocalNames: MutableSet<String>?
        get() = localNamesStack.lastOrNull()
    private val currentShadowedLocalNames: MutableSet<String>?
        get() = localShadowedNamesStack.lastOrNull()

    private data class SlotEntry(val index: Int, val isMutable: Boolean, val isDelegated: Boolean)
    private data class SlotPlan(val slots: MutableMap<String, SlotEntry>, var nextIndex: Int, val id: Int)
    private data class SlotLocation(
        val slot: Int,
        val depth: Int,
        val scopeId: Int,
        val isMutable: Boolean,
        val isDelegated: Boolean
    )
    private val slotPlanStack = mutableListOf<SlotPlan>()
    private var nextScopeId = 0
    private val genericFunctionDeclsStack = mutableListOf<MutableMap<String, GenericFunctionDecl>>(mutableMapOf())

    // Track declared local variables count per function for precise capacity hints
    private val localDeclCountStack = mutableListOf<Int>()
    private val currentLocalDeclCount: Int
        get() = localDeclCountStack.lastOrNull() ?: 0

    private data class GenericFunctionDecl(
        val typeParams: List<TypeDecl.TypeParam>,
        val params: List<ArgsDeclaration.Item>,
        val pos: Pos
    )

    private fun pushGenericFunctionScope() {
        genericFunctionDeclsStack.add(mutableMapOf())
    }

    private fun popGenericFunctionScope() {
        genericFunctionDeclsStack.removeLast()
    }

    private fun currentGenericFunctionDecls(): MutableMap<String, GenericFunctionDecl> {
        return genericFunctionDeclsStack.last()
    }

    private fun lookupGenericFunctionDecl(name: String): GenericFunctionDecl? {
        for (i in genericFunctionDeclsStack.indices.reversed()) {
            genericFunctionDeclsStack[i][name]?.let { return it }
        }
        return null
    }

    private inline fun <T> withLocalNames(names: Set<String>, block: () -> T): T {
        localNamesStack.add(names.toMutableSet())
        localShadowedNamesStack.add(mutableSetOf())
        return try {
            block()
        } finally {
            localShadowedNamesStack.removeLast()
            localNamesStack.removeLast()
        }
    }

    private fun declareLocalName(name: String, isMutable: Boolean, isDelegated: Boolean = false) {
        // Add to current function's local set; only count if it was newly added (avoid duplicates)
        val added = currentLocalNames?.add(name) == true
        if (!added) {
            currentShadowedLocalNames?.add(name)
        }
        if (added) {
            scopeSeedNames.remove(name)
        }
        if (added && localDeclCountStack.isNotEmpty()) {
            localDeclCountStack[localDeclCountStack.lastIndex] = currentLocalDeclCount + 1
        }
        capturePlanStack.lastOrNull()?.let { plan ->
            if (plan.captureMap.remove(name) != null) {
                plan.captureOwners.remove(name)
                plan.captures.removeAll { it.name == name }
            }
        }
        declareSlotName(name, isMutable, isDelegated)
    }

    private fun declareSlotName(name: String, isMutable: Boolean, isDelegated: Boolean) {
        if (codeContexts.lastOrNull() is CodeContext.ClassBody) return
        val plan = slotPlanStack.lastOrNull() ?: return
        if (plan.slots.containsKey(name)) return
        plan.slots[name] = SlotEntry(plan.nextIndex, isMutable, isDelegated)
        plan.nextIndex += 1
        if (!seedingSlotPlan && plan == moduleSlotPlan()) {
            moduleDeclaredNames.add(name)
        }
    }

    private fun declareSlotNameIn(plan: SlotPlan, name: String, isMutable: Boolean, isDelegated: Boolean) {
        if (plan.slots.containsKey(name)) return
        plan.slots[name] = SlotEntry(plan.nextIndex, isMutable, isDelegated)
        plan.nextIndex += 1
    }

    private fun declareSlotNameAt(
        plan: SlotPlan,
        name: String,
        slotIndex: Int,
        isMutable: Boolean,
        isDelegated: Boolean
    ) {
        if (plan.slots.containsKey(name)) return
        plan.slots[name] = SlotEntry(slotIndex, isMutable, isDelegated)
        if (slotIndex >= plan.nextIndex) {
            plan.nextIndex = slotIndex + 1
        }
    }

    private fun moduleSlotPlan(): SlotPlan? = slotPlanStack.firstOrNull()
    private val slotTypeByScopeId: MutableMap<Int, MutableMap<Int, ObjClass>> = mutableMapOf()
    private val nameObjClass: MutableMap<String, ObjClass> = mutableMapOf()
    private val scopeSeedNames: MutableSet<String> = mutableSetOf()
    private val slotTypeDeclByScopeId: MutableMap<Int, MutableMap<Int, TypeDecl>> = mutableMapOf()
    private val nameTypeDecl: MutableMap<String, TypeDecl> = mutableMapOf()
    private data class TypeAliasDecl(
        val name: String,
        val typeParams: List<TypeDecl.TypeParam>,
        val body: TypeDecl,
        val pos: Pos
    )
    private val typeAliases: MutableMap<String, TypeAliasDecl> = mutableMapOf()
    private val methodReturnTypeDeclByRef: MutableMap<ObjRef, TypeDecl> = mutableMapOf()
    private val callReturnTypeDeclByRef: MutableMap<CallRef, TypeDecl> = mutableMapOf()
    private val callableReturnTypeByScopeId: MutableMap<Int, MutableMap<Int, ObjClass>> = mutableMapOf()
    private val callableReturnTypeByName: MutableMap<String, ObjClass> = mutableMapOf()
    private val callableReturnTypeDeclByName: MutableMap<String, TypeDecl> = mutableMapOf()
    private val lambdaReturnTypeByRef: MutableMap<ObjRef, ObjClass> = mutableMapOf()
    private val lambdaCaptureEntriesByRef: MutableMap<ValueFnRef, List<net.sergeych.lyng.bytecode.LambdaCaptureEntry>> =
        mutableMapOf()
    private val classFieldTypesByName: MutableMap<String, MutableMap<String, ObjClass>> = mutableMapOf()
    private val classMethodReturnTypeByName: MutableMap<String, MutableMap<String, ObjClass>> = mutableMapOf()
    private val classMethodReturnTypeDeclByName: MutableMap<String, MutableMap<String, TypeDecl>> = mutableMapOf()
    private val classScopeMembersByClassName: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val classScopeCallableMembersByClassName: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val encodedPayloadTypeByScopeId: MutableMap<Int, MutableMap<Int, ObjClass>> = mutableMapOf()
    private val encodedPayloadTypeByName: MutableMap<String, ObjClass> = mutableMapOf()
    private val objectDeclNames: MutableSet<String> = mutableSetOf()
    private val externCallableNames: MutableSet<String> = mutableSetOf()
    private val externBindingNames: MutableSet<String> = mutableSetOf()
    private val moduleDeclaredNames: MutableSet<String> = mutableSetOf()
    private val predeclaredTopLevelValueNames: MutableSet<String> = mutableSetOf()
    private val moduleReferencePosByName: MutableMap<String, Pos> = mutableMapOf()
    private var seedingSlotPlan: Boolean = false

    private fun moduleForcedLocalSlotInfo(): Map<String, ForcedLocalSlotInfo> {
        val plan = moduleSlotPlan() ?: return emptyMap()
        if (plan.slots.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, ForcedLocalSlotInfo>(plan.slots.size)
        for ((name, entry) in plan.slots) {
            if (externBindingNames.contains(name)) continue
            result[name] = ForcedLocalSlotInfo(
                index = entry.index,
                isMutable = entry.isMutable,
                isDelegated = entry.isDelegated
            )
        }
        return result
    }

    private fun seedSlotPlanFromScope(scope: Scope, includeParents: Boolean = false) {
        val plan = moduleSlotPlan() ?: return
        seedingSlotPlan = true
        try {
            var current: Scope? = scope
            while (current != null) {
                for ((name, record) in current.objects) {
                    if (!record.visibility.isPublic) continue
                    if (plan.slots.containsKey(name)) continue
                    declareSlotNameIn(plan, name, record.isMutable, record.type == ObjRecord.Type.Delegated)
                    scopeSeedNames.add(name)
                    if (record.typeDecl != null && nameTypeDecl[name] == null) {
                        nameTypeDecl[name] = record.typeDecl
                        if (nameObjClass[name] == null) {
                            resolveTypeDeclObjClass(record.typeDecl)?.let { nameObjClass[name] = it }
                        }
                    }
                    val instance = record.value as? ObjInstance
                    if (instance != null && nameObjClass[name] == null) {
                        nameObjClass[name] = instance.objClass
                    }
                }
                for ((cls, map) in current.extensions) {
                    for ((name, record) in map) {
                        if (!record.visibility.isPublic) continue
                        when (record.type) {
                            ObjRecord.Type.Property -> {
                                val getterName = extensionPropertyGetterName(cls.className, name)
                                if (!plan.slots.containsKey(getterName)) {
                                    declareSlotNameIn(
                                        plan,
                                        getterName,
                                        isMutable = false,
                                        isDelegated = false
                                    )
                                    scopeSeedNames.add(getterName)
                                }
                                val prop = record.value as? ObjProperty
                                if (prop?.setter != null) {
                                    val setterName = extensionPropertySetterName(cls.className, name)
                                    if (!plan.slots.containsKey(setterName)) {
                                        declareSlotNameIn(
                                            plan,
                                            setterName,
                                            isMutable = false,
                                            isDelegated = false
                                        )
                                        scopeSeedNames.add(setterName)
                                    }
                                }
                            }
                            else -> {
                                val callableName = extensionCallableName(cls.className, name)
                                if (!plan.slots.containsKey(callableName)) {
                                    declareSlotNameIn(
                                        plan,
                                        callableName,
                                        isMutable = false,
                                        isDelegated = false
                                    )
                                    scopeSeedNames.add(callableName)
                                }
                            }
                        }
                    }
                }
                for ((name, slotIndex) in current.slotNameToIndexSnapshot()) {
                    val record = current.getSlotRecord(slotIndex)
                    if (!record.visibility.isPublic) continue
                    if (plan.slots.containsKey(name)) continue
                    declareSlotNameIn(plan, name, record.isMutable, record.type == ObjRecord.Type.Delegated)
                    scopeSeedNames.add(name)
                }
                if (!includeParents) return
                current = current.parent
            }
        } finally {
            seedingSlotPlan = false
        }
    }

    private fun seedSlotPlanFromSeedScope(scope: Scope) {
        val plan = moduleSlotPlan() ?: return
        for ((name, slotIndex) in scope.slotNameToIndexSnapshot()) {
            val record = scope.getSlotRecord(slotIndex)
            declareSlotNameAt(
                plan,
                name,
                slotIndex,
                record.isMutable,
                record.type == ObjRecord.Type.Delegated
            )
            scopeSeedNames.add(name)
            if (record.typeDecl != null && nameTypeDecl[name] == null) {
                nameTypeDecl[name] = record.typeDecl
                if (nameObjClass[name] == null) {
                    resolveTypeDeclObjClass(record.typeDecl)?.let { nameObjClass[name] = it }
                }
            }
            if (record.typeDecl != null) {
                slotTypeDeclByScopeId.getOrPut(plan.id) { mutableMapOf() }[slotIndex] = record.typeDecl
            }
            val resolved = when (val raw = record.value) {
                is FrameSlotRef -> raw.peekValue() ?: raw.read()
                is RecordSlotRef -> raw.peekValue() ?: raw.read()
                else -> raw
            }
            when (resolved) {
                is ObjClass -> {
                    slotTypeByScopeId.getOrPut(plan.id) { mutableMapOf() }[slotIndex] = resolved
                    if (nameObjClass[name] == null) nameObjClass[name] = resolved
                }
                is ObjInstance -> {
                    slotTypeByScopeId.getOrPut(plan.id) { mutableMapOf() }[slotIndex] = resolved.objClass
                    if (nameObjClass[name] == null) nameObjClass[name] = resolved.objClass
                }
                is ObjDynamic -> {
                    slotTypeByScopeId.getOrPut(plan.id) { mutableMapOf() }[slotIndex] = resolved.objClass
                    if (nameObjClass[name] == null) nameObjClass[name] = resolved.objClass
                }
            }
        }
    }

    private fun predeclareTopLevelSymbols() {
        val plan = moduleSlotPlan() ?: return
        val saved = cc.savePos()
        var depth = 0
        var parenDepth = 0
        var bracketDepth = 0
        fun nextNonWs(): Token {
            var t = cc.next()
            while (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINGLE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                t = cc.next()
            }
            return t
        }
        fun handleTopLevelKeyword(keyword: Token): Boolean {
            when (keyword.value) {
                "fun", "fn" -> {
                    val nameToken = nextNonWs()
                    if (nameToken.type != Token.Type.ID) return true
                    val afterName = cc.peekNextNonWhitespace()
                    if (afterName.type == Token.Type.DOT) {
                        cc.nextNonWhitespace()
                        val actual = cc.nextNonWhitespace()
                        if (actual.type == Token.Type.ID) {
                            extensionNames.add(actual.value)
                            registerExtensionName(nameToken.value, actual.value)
                            declareSlotNameIn(plan, extensionCallableName(nameToken.value, actual.value), isMutable = false, isDelegated = false)
                            moduleDeclaredNames.add(extensionCallableName(nameToken.value, actual.value))
                        }
                        return true
                    }
                    declareSlotNameIn(plan, nameToken.value, isMutable = false, isDelegated = false)
                    moduleDeclaredNames.add(nameToken.value)
                    return true
                }
                "val", "var" -> {
                    val nameToken = nextNonWs()
                    if (nameToken.type != Token.Type.ID) return true
                    val afterName = cc.peekNextNonWhitespace()
                    if (afterName.type == Token.Type.DOT) {
                        cc.nextNonWhitespace()
                        val actual = cc.nextNonWhitespace()
                        if (actual.type == Token.Type.ID) {
                            extensionNames.add(actual.value)
                            registerExtensionName(nameToken.value, actual.value)
                            declareSlotNameIn(plan, extensionPropertyGetterName(nameToken.value, actual.value), isMutable = false, isDelegated = false)
                            moduleDeclaredNames.add(extensionPropertyGetterName(nameToken.value, actual.value))
                            if (keyword.value == "var") {
                                declareSlotNameIn(plan, extensionPropertySetterName(nameToken.value, actual.value), isMutable = false, isDelegated = false)
                                moduleDeclaredNames.add(extensionPropertySetterName(nameToken.value, actual.value))
                            }
                        }
                        return true
                    }
                    declareSlotNameIn(plan, nameToken.value, isMutable = keyword.value == "var", isDelegated = false)
                    moduleDeclaredNames.add(nameToken.value)
                    predeclaredTopLevelValueNames.add(nameToken.value)
                    return true
                }
                "class", "object" -> {
                    val nameToken = nextNonWs()
                    if (nameToken.type == Token.Type.ID) {
                        declareSlotNameIn(plan, nameToken.value, isMutable = false, isDelegated = false)
                        scopeSeedNames.add(nameToken.value)
                        moduleDeclaredNames.add(nameToken.value)
                    }
                    return true
                }
                "enum" -> {
                    val next = nextNonWs()
                    val nameToken = if (next.type == Token.Type.ID && next.value == "class") nextNonWs() else next
                    if (nameToken.type == Token.Type.ID) {
                        declareSlotNameIn(plan, nameToken.value, isMutable = false, isDelegated = false)
                        scopeSeedNames.add(nameToken.value)
                        moduleDeclaredNames.add(nameToken.value)
                    }
                    return true
                }
            }
            return false
        }
        try {
            while (cc.hasNext()) {
                val t = cc.next()
                when (t.type) {
                    Token.Type.LBRACE -> depth++
                    Token.Type.RBRACE -> if (depth > 0) depth--
                    Token.Type.LPAREN -> parenDepth++
                    Token.Type.RPAREN -> if (parenDepth > 0) parenDepth--
                    Token.Type.LBRACKET -> bracketDepth++
                    Token.Type.RBRACKET -> if (bracketDepth > 0) bracketDepth--
                    Token.Type.ID -> if (depth == 0) {
                        if (parenDepth > 0 || bracketDepth > 0) continue
                        if (t.value == "extern") {
                            handleTopLevelKeyword(nextNonWs())
                            continue
                        }
                        handleTopLevelKeyword(t)
                    }
                    else -> {}
                }
            }
        } finally {
            cc.restorePos(saved)
        }
    }

    private fun rememberModuleReferencePos(name: String, pos: Pos) {
        if (!moduleReferencePosByName.containsKey(name)) {
            moduleReferencePosByName[name] = pos
        }
    }

    private fun predeclareClassMembers(target: MutableSet<String>, overrides: MutableMap<String, Boolean>) {
        val saved = cc.savePos()
        var depth = 0
        val modifiers = setOf(
            "public", "private", "protected", "internal",
            "override", "abstract", "extern", "static", "transient"
        )
        fun nextNonWs(): Token {
            var t = cc.next()
            while (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINGLE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                t = cc.next()
            }
            return t
        }
        try {
            while (cc.hasNext()) {
                var t = cc.next()
                when (t.type) {
                    Token.Type.LBRACE -> depth++
                    Token.Type.RBRACE -> if (depth == 0) break else depth--
                    Token.Type.ID -> if (depth == 0) {
                        var sawOverride = false
                        while (t.type == Token.Type.ID && t.value in modifiers) {
                            if (t.value == "override") sawOverride = true
                            t = nextNonWs()
                        }
                        when (t.value) {
                            "fun", "fn", "val", "var" -> {
                                val nameToken = nextNonWs()
                                if (nameToken.type == Token.Type.ID) {
                                    val afterName = cc.peekNextNonWhitespace()
                                    if (afterName.type != Token.Type.DOT) {
                                        target.add(nameToken.value)
                                        overrides[nameToken.value] = sawOverride
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        } finally {
            cc.restorePos(saved)
        }
    }

    private fun predeclareClassScopeMembers(
        className: String,
        target: MutableSet<String>,
        callableTarget: MutableSet<String>
    ) {
        val saved = cc.savePos()
        var depth = 0
        val modifiers = setOf(
            "public", "private", "protected", "internal",
            "override", "abstract", "extern", "static", "transient", "open", "closed"
        )
        fun nextNonWs(): Token {
            var t = cc.next()
            while (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINGLE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                t = cc.next()
            }
            return t
        }
        try {
            while (cc.hasNext()) {
                var t = cc.next()
                when (t.type) {
                    Token.Type.LBRACE -> depth++
                    Token.Type.RBRACE -> if (depth == 0) break else depth--
                    Token.Type.ID -> if (depth == 0) {
                        var sawStatic = false
                        while (t.type == Token.Type.ID && t.value in modifiers) {
                            if (t.value == "static") sawStatic = true
                            t = nextNonWs()
                        }
                        when (t.value) {
                            "class" -> {
                                val nameToken = nextNonWs()
                                if (nameToken.type == Token.Type.ID) {
                                    target.add(nameToken.value)
                                    callableTarget.add(nameToken.value)
                                    registerClassScopeMember(className, nameToken.value)
                                    registerClassScopeCallableMember(className, nameToken.value)
                                }
                            }
                            "object" -> {
                                val nameToken = nextNonWs()
                                if (nameToken.type == Token.Type.ID) {
                                    target.add(nameToken.value)
                                    registerClassScopeMember(className, nameToken.value)
                                }
                            }
                            "enum" -> {
                                val next = nextNonWs()
                                val nameToken = if (next.type == Token.Type.ID && next.value == "class") nextNonWs() else next
                                if (nameToken.type == Token.Type.ID) {
                                    target.add(nameToken.value)
                                    callableTarget.add(nameToken.value)
                                    registerClassScopeMember(className, nameToken.value)
                                    registerClassScopeCallableMember(className, nameToken.value)
                                }
                            }
                            "type" -> {
                                val nameToken = nextNonWs()
                                if (nameToken.type == Token.Type.ID) {
                                    target.add(nameToken.value)
                                    registerClassScopeMember(className, nameToken.value)
                                }
                            }
                            "fun", "fn", "val", "var" -> {
                                if (sawStatic) {
                                    val nameToken = nextNonWs()
                                    if (nameToken.type == Token.Type.ID) {
                                        target.add(nameToken.value)
                                        registerClassScopeMember(className, nameToken.value)
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        } finally {
            cc.restorePos(saved)
        }
    }

    private fun registerClassScopeMember(className: String, name: String) {
        classScopeMembersByClassName.getOrPut(className) { mutableSetOf() }.add(name)
    }

    private fun registerClassScopeCallableMember(className: String, name: String) {
        classScopeCallableMembersByClassName.getOrPut(className) { mutableSetOf() }.add(name)
    }

    private fun isClassScopeCallableMember(className: String, name: String): Boolean {
        return classScopeCallableMembersByClassName[className]?.contains(name) == true
    }

    private fun registerClassScopeFieldType(ownerClassName: String?, memberName: String, memberClassName: String) {
        if (ownerClassName == null) return
        val memberClass = resolveClassByName(memberClassName) ?: return
        classFieldTypesByName.getOrPut(ownerClassName) { mutableMapOf() }[memberName] = memberClass
    }

    private fun resolveCompileClassInfo(name: String): CompileClassInfo? {
        compileClassInfos[name]?.let { return it }
        val scopeRec = seedScope?.get(name) ?: importManager.rootScope.get(name)
        val clsFromScope = scopeRec?.value as? ObjClass
        val clsFromImports = if (clsFromScope == null) {
            importedModules.asReversed().firstNotNullOfOrNull { it.scope.get(name)?.value as? ObjClass }
        } else {
            null
        }
        val cls = clsFromScope ?: clsFromImports ?: resolveClassByName(name) ?: return null
        val fieldIds = cls.instanceFieldIdMap()
        val methodIds = cls.instanceMethodIdMap(includeAbstract = true)
        val baseNames = cls.directParents.map { it.className }
        val nextFieldId = (fieldIds.values.maxOrNull() ?: -1) + 1
        val nextMethodId = (methodIds.values.maxOrNull() ?: -1) + 1
        return CompileClassInfo(name, cls.logicalPackageName, fieldIds, methodIds, nextFieldId, nextMethodId, baseNames)
    }

    private data class BaseMemberIds(
        val fieldIds: Map<String, Int>,
        val methodIds: Map<String, Int>,
        val fieldConflicts: Set<String>,
        val methodConflicts: Set<String>,
        val nextFieldId: Int,
        val nextMethodId: Int
    )

    private fun collectBaseMemberIds(baseNames: List<String>): BaseMemberIds {
        val allBaseNames = if (baseNames.contains("Object")) baseNames else baseNames + "Object"
        val fieldIds = mutableMapOf<String, Int>()
        val methodIds = mutableMapOf<String, Int>()
        val fieldConflicts = mutableSetOf<String>()
        val methodConflicts = mutableSetOf<String>()
        var maxFieldId = -1
        var maxMethodId = -1
        for (base in allBaseNames) {
            val info = resolveCompileClassInfo(base) ?: continue
            for ((name, id) in info.fieldIds) {
                val prev = fieldIds[name]
                if (prev == null) fieldIds[name] = id
                else if (prev != id) fieldConflicts.add(name)
                if (id > maxFieldId) maxFieldId = id
            }
            for ((name, id) in info.methodIds) {
                val prev = methodIds[name]
                if (prev == null) methodIds[name] = id
                else if (prev != id) methodConflicts.add(name)
                if (id > maxMethodId) maxMethodId = id
            }
        }
        return BaseMemberIds(
            fieldIds = fieldIds,
            methodIds = methodIds,
            fieldConflicts = fieldConflicts,
            methodConflicts = methodConflicts,
            nextFieldId = maxFieldId + 1,
            nextMethodId = maxMethodId + 1
        )
    }

    private fun buildParamSlotPlan(names: List<String>): SlotPlan {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        for (name in names) {
            if (!map.containsKey(name)) {
                map[name] = idx
                idx++
            }
        }
        val entries = mutableMapOf<String, SlotEntry>()
        for ((name, index) in map) {
            entries[name] = SlotEntry(index, isMutable = false, isDelegated = false)
        }
        return SlotPlan(entries, idx, nextScopeId++)
    }

    private fun markDelegatedSlot(name: String) {
        val plan = slotPlanStack.lastOrNull() ?: return
        val entry = plan.slots[name] ?: return
        if (!entry.isDelegated) {
            plan.slots[name] = entry.copy(isDelegated = true)
        }
    }

    private fun slotPlanIndices(plan: SlotPlan): Map<String, Int> {
        if (plan.slots.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Int>(plan.slots.size)
        for ((name, entry) in plan.slots) {
            result[name] = entry.index
        }
        return result
    }

    private fun callSignatureForName(name: String): CallSignature? {
        seedScope?.getLocalRecordDirect(name)?.callSignature?.let { return it }
        return seedScope?.get(name)?.callSignature
            ?: importManager.rootScope.getLocalRecordDirect(name)?.callSignature
    }

    internal data class MemberIds(val fieldId: Int?, val methodId: Int?)

    private fun resolveMemberIds(name: String, pos: Pos, qualifier: String? = null): MemberIds {
        val ctx = if (qualifier == null) {
            codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        } else null
        if (ctx != null) {
            val fieldId = ctx.memberFieldIds[name]
            val methodId = ctx.memberMethodIds[name]
            if (fieldId == null && methodId == null) {
                if (allowUnresolvedRefs) return MemberIds(null, null)
                throw ScriptError(pos, "unknown member $name")
            }
            return MemberIds(fieldId, methodId)
        }
        if (qualifier != null) {
            val classCtx = codeContexts.asReversed()
                .firstOrNull { it is CodeContext.ClassBody && it.name == qualifier } as? CodeContext.ClassBody
            if (classCtx != null) {
                val fieldId = classCtx.memberFieldIds[name]
                val methodId = classCtx.memberMethodIds[name]
                if (fieldId != null || methodId != null) return MemberIds(fieldId, methodId)
            }
            val info = resolveCompileClassInfo(qualifier)
                ?: if (allowUnresolvedRefs) return MemberIds(null, null) else throw ScriptError(pos, "unknown type $qualifier")
            val fieldId = info.fieldIds[name]
            val methodId = info.methodIds[name]
            if (fieldId == null && methodId == null) {
                if (allowUnresolvedRefs) return MemberIds(null, null)
                throw ScriptError(pos, "unknown member $name on $qualifier")
            }
            return MemberIds(fieldId, methodId)
        }
        if (allowUnresolvedRefs) return MemberIds(null, null)
        throw ScriptError(pos, "member $name is not available without class context")
    }

    private fun tailBlockReceiverType(left: ObjRef): String? {
        val name = when (left) {
            is LocalVarRef -> left.name
            is LocalSlotRef -> left.name
            is ImplicitThisMemberRef -> left.name
            else -> null
        }
        if (name == null) return null
        val signature = callSignatureForName(name)
        return signature?.tailBlockReceiverType ?: if (name == "flow") "FlowBuilder" else null
    }

    private fun currentImplicitThisTypeName(): String? {
        for (ctx in codeContexts.asReversed()) {
            val fn = ctx as? CodeContext.Function ?: continue
            if (fn.implicitThisTypeName != null) return fn.implicitThisTypeName
        }
        return null
    }

    private fun implicitReceiverTypeForMember(name: String): String? {
        for (ctx in codeContexts.asReversed()) {
            val fn = ctx as? CodeContext.Function ?: continue
            if (!fn.implicitThisMembers) continue
            val typeName = fn.implicitThisTypeName ?: continue
            if (hasImplicitThisMember(name, typeName)) return typeName
        }
        return null
    }

    private fun currentEnclosingClassName(): String? {
        val ctx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        return ctx?.name
    }

    private fun currentTypeParams(): Set<String> {
        val result = mutableSetOf<String>()
        pendingTypeParamStack.lastOrNull()?.let { result.addAll(it) }
        for (ctx in codeContexts.asReversed()) {
            when (ctx) {
                is CodeContext.Function -> result.addAll(ctx.typeParams)
                is CodeContext.ClassBody -> result.addAll(ctx.typeParams)
                else -> {}
            }
        }
        return result
    }

    private val pendingTypeParamStack = mutableListOf<Set<String>>()

    private fun parseTypeParamList(): List<TypeDecl.TypeParam> {
        if (cc.peekNextNonWhitespace().type != Token.Type.LT) return emptyList()
        val typeParams = mutableListOf<TypeDecl.TypeParam>()
        cc.nextNonWhitespace()
        while (true) {
            val varianceToken = cc.peekNextNonWhitespace()
            val variance = when (varianceToken.value) {
                "in" -> {
                    cc.nextNonWhitespace()
                    TypeDecl.Variance.In
                }
                "out" -> {
                    cc.nextNonWhitespace()
                    TypeDecl.Variance.Out
                }
                else -> TypeDecl.Variance.Invariant
            }
            val idTok = cc.requireToken(Token.Type.ID, "type parameter name expected")
            var bound: TypeDecl? = null
            var defaultType: TypeDecl? = null
            if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
                bound = parseTypeExpressionWithMini().first
            }
            if (cc.skipTokenOfType(Token.Type.ASSIGN, isOptional = true)) {
                defaultType = parseTypeExpressionWithMini().first
            }
            typeParams.add(TypeDecl.TypeParam(idTok.value, variance, bound, defaultType))
            val sep = cc.nextNonWhitespace()
            when (sep.type) {
                Token.Type.COMMA -> continue
                Token.Type.GT -> break
                Token.Type.SHR -> {
                    cc.pushPendingGT()
                    break
                }
                else -> sep.raiseSyntax("expected ',' or '>' in type parameter list")
            }
        }
        return typeParams
    }

    private fun looksLikeTypeAliasDeclaration(): Boolean {
        val saved = cc.savePos()
        try {
            val nameTok = cc.nextNonWhitespace()
            if (nameTok.type != Token.Type.ID) return false
            val afterName = cc.savePos()
            if (cc.skipTokenOfType(Token.Type.LT, isOptional = true)) {
                var depth = 1
                while (depth > 0) {
                    val t = cc.nextNonWhitespace()
                    when (t.type) {
                        Token.Type.LT -> depth += 1
                        Token.Type.GT -> depth -= 1
                        Token.Type.SHR -> {
                            cc.pushPendingGT()
                            depth -= 1
                        }
                        Token.Type.EOF -> return false
                        else -> {}
                    }
                }
            } else {
                cc.restorePos(afterName)
            }
            cc.skipWsTokens()
            return cc.peekNextNonWhitespace().type == Token.Type.ASSIGN
        } finally {
            cc.restorePos(saved)
        }
    }

    private suspend fun parseTypeAliasDeclaration(): Statement {
        val nameToken = cc.requireToken(Token.Type.ID, "type alias name expected")
        val startPos = pendingDeclStart ?: nameToken.pos
        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null
        val declaredName = nameToken.value
        val outerClassName = currentEnclosingClassName()
        val qualifiedName = if (outerClassName != null) "$outerClassName.$declaredName" else declaredName
        if (typeAliases.containsKey(qualifiedName)) {
            throw ScriptError(nameToken.pos, "type alias $qualifiedName already declared")
        }
        if (resolveTypeDeclObjClass(TypeDecl.Simple(qualifiedName, false)) != null) {
            throw ScriptError(nameToken.pos, "type alias $qualifiedName conflicts with existing class")
        }
        val typeParams = parseTypeParamList()
        val uniqueParams = typeParams.map { it.name }.toSet()
        if (uniqueParams.size != typeParams.size) {
            throw ScriptError(nameToken.pos, "type alias $qualifiedName has duplicate type parameters")
        }
        val typeParamNames = uniqueParams
        if (typeParamNames.isNotEmpty()) pendingTypeParamStack.add(typeParamNames)
        val (body, bodyMini) = try {
            cc.skipWsTokens()
            val eq = cc.nextNonWhitespace()
            if (eq.type != Token.Type.ASSIGN) {
                throw ScriptError(eq.pos, "type alias $qualifiedName expects '='")
            }
            parseTypeExpressionWithMini()
        } finally {
            if (typeParamNames.isNotEmpty()) pendingTypeParamStack.removeLast()
        }
        val alias = TypeAliasDecl(qualifiedName, typeParams, body, nameToken.pos)
        typeAliases[qualifiedName] = alias
        declareLocalName(declaredName, isMutable = false)
        resolutionSink?.declareSymbol(declaredName, SymbolKind.LOCAL, isMutable = false, pos = nameToken.pos)
        if (outerClassName != null) {
            val outerCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            outerCtx?.classScopeMembers?.add(declaredName)
            registerClassScopeMember(outerClassName, declaredName)
        }
        miniSink?.onTypeAliasDecl(
            MiniTypeAliasDecl(
                range = MiniRange(startPos, cc.currentPos()),
                name = declaredName,
                typeParams = typeParams.map { it.name },
                target = bodyMini,
                doc = doc,
                nameStart = nameToken.pos
            )
        )

        val aliasExpr = net.sergeych.lyng.obj.TypeDeclRef(body, nameToken.pos)
        val initStmt = ExpressionStatement(aliasExpr, nameToken.pos)
        val slotPlan = slotPlanStack.lastOrNull()
        val slotIndex = slotPlan?.slots?.get(declaredName)?.index
        val scopeId = slotPlan?.id
        return VarDeclStatement(
            name = declaredName,
            isMutable = false,
            visibility = Visibility.Public,
            actualExtern = false,
            initializer = initStmt,
            isTransient = false,
            typeDecl = null,
            slotIndex = slotIndex,
            scopeId = scopeId,
            startPos = nameToken.pos,
            initializerObjClass = null
        )
    }

    private fun lookupSlotLocation(name: String, includeModule: Boolean = true): SlotLocation? {
        for (i in slotPlanStack.indices.reversed()) {
            if (!includeModule && i == 0) continue
            val slot = slotPlanStack[i].slots[name] ?: continue
            val depth = slotPlanStack.size - 1 - i
            return SlotLocation(slot.index, depth, slotPlanStack[i].id, slot.isMutable, slot.isDelegated)
        }
        return null
    }

    private fun resolveIdentifierRef(name: String, pos: Pos): ObjRef {
        if (name == "__PACKAGE__") {
            resolutionSink?.reference(name, pos)
            val value = ObjString(packageName ?: "unknown").asReadonly
            return ConstRef(value)
        }
        if (name == "this") {
            resolutionSink?.reference(name, pos)
            return LocalVarRef(name, pos)
        }
        val slotLoc = lookupSlotLocation(name, includeModule = false)
        if (slotLoc != null) {
            val classCtx = codeContexts.lastOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            if (classCtx?.slotPlanId == slotLoc.scopeId &&
                classCtx.declaredMembers.contains(name)
            ) {
                val fieldId = classCtx.memberFieldIds[name]
                val methodId = classCtx.memberMethodIds[name]
                if (fieldId != null || methodId != null) {
                    resolutionSink?.referenceMember(name, pos)
                    return ImplicitThisMemberRef(name, pos, fieldId, methodId, classCtx.name)
                }
            }
            captureLocalRef(name, slotLoc, pos)?.let { ref ->
                rememberModuleReferencePos(name, pos)
                resolutionSink?.reference(name, pos)
                return ref
            }
            val captureOwner = capturePlanStack.lastOrNull()?.captureOwners?.get(name)
            if (useFastLocalRefs &&
                slotLoc.depth == 0 &&
                captureOwner == null &&
                currentLocalNames?.contains(name) == true &&
                currentShadowedLocalNames?.contains(name) != true &&
                !slotLoc.isDelegated
            ) {
                resolutionSink?.reference(name, pos)
                return FastLocalVarRef(name, pos)
            }
            if (slotLoc.depth == 0 && captureOwner != null) {
                val ref = LocalSlotRef(
                    name,
                    slotLoc.slot,
                    slotLoc.scopeId,
                    slotLoc.isMutable,
                    slotLoc.isDelegated,
                    pos,
                    strictSlotRefs,
                    captureOwnerScopeId = captureOwner.scopeId,
                    captureOwnerSlot = captureOwner.slot
                )
                resolutionSink?.reference(name, pos)
                return ref
            }
            val ref = LocalSlotRef(
                name,
                slotLoc.slot,
                slotLoc.scopeId,
                slotLoc.isMutable,
                slotLoc.isDelegated,
                pos,
                strictSlotRefs
            )
            resolutionSink?.reference(name, pos)
            return ref
        }
        val moduleLoc = if (slotPlanStack.size == 1) lookupSlotLocation(name, includeModule = true) else null
        if (moduleLoc != null) {
            val moduleDeclaredNames = localNamesStack.firstOrNull()
            if (moduleDeclaredNames == null || !moduleDeclaredNames.contains(name)) {
                val resolvedImport = resolveImportBinding(name, pos)
                if (resolvedImport != null) {
                    registerImportBinding(name, resolvedImport.binding, pos)
                } else if (predeclaredTopLevelValueNames.contains(name)) {
                    throw ScriptError(pos, "symbol '$name' is not defined")
                }
            }
            val ref = LocalSlotRef(
                name,
                moduleLoc.slot,
                moduleLoc.scopeId,
                moduleLoc.isMutable,
                moduleLoc.isDelegated,
                pos,
                strictSlotRefs
            )
            resolutionSink?.reference(name, pos)
            return ref
        }
        val classCtx = codeContexts.lastOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        if (classCtx != null && classCtx.declaredMembers.contains(name)) {
            resolutionSink?.referenceMember(name, pos)
            val ids = resolveMemberIds(name, pos, null)
            return ImplicitThisMemberRef(name, pos, ids.fieldId, ids.methodId, currentImplicitThisTypeName())
        }
        val implicitTypeFromFunc = implicitReceiverTypeForMember(name)
        val hasImplicitClassMember = classCtx != null && hasImplicitThisMember(name, classCtx.name)
        if (implicitTypeFromFunc == null && !hasImplicitClassMember) {
            val modulePlan = moduleSlotPlan()
            val moduleEntry = modulePlan?.slots?.get(name)
            if (moduleEntry != null) {
                val moduleDeclaredNames = localNamesStack.firstOrNull()
                if (moduleDeclaredNames == null || !moduleDeclaredNames.contains(name)) {
                    val resolvedImport = resolveImportBinding(name, pos)
                    if (resolvedImport != null) {
                        registerImportBinding(name, resolvedImport.binding, pos)
                    } else if (predeclaredTopLevelValueNames.contains(name)) {
                        throw ScriptError(pos, "symbol '$name' is not defined")
                    }
                }
                val moduleLoc = SlotLocation(
                    moduleEntry.index,
                    slotPlanStack.size - 1,
                    modulePlan.id,
                    moduleEntry.isMutable,
                    moduleEntry.isDelegated
                )
                captureLocalRef(name, moduleLoc, pos)?.let { ref ->
                    rememberModuleReferencePos(name, pos)
                    resolutionSink?.reference(name, pos)
                    return ref
                }
                val ref = if (!useScopeSlots && capturePlanStack.isEmpty() && moduleLoc.depth > 0) {
                    LocalSlotRef(
                        name,
                        moduleLoc.slot,
                        moduleLoc.scopeId,
                        moduleLoc.isMutable,
                        moduleLoc.isDelegated,
                        pos,
                        strictSlotRefs,
                        captureOwnerScopeId = moduleLoc.scopeId,
                        captureOwnerSlot = moduleLoc.slot
                    )
                } else {
                    LocalSlotRef(
                        name,
                        moduleLoc.slot,
                        moduleLoc.scopeId,
                        moduleLoc.isMutable,
                        moduleLoc.isDelegated,
                        pos,
                        strictSlotRefs
                    )
                }
                rememberModuleReferencePos(name, pos)
                resolutionSink?.reference(name, pos)
                return ref
            }
            resolveImportBinding(name, pos)?.let { resolved ->
                val sourceRecord = resolved.record
                if (modulePlan != null && !modulePlan.slots.containsKey(name)) {
                    val seedSlotIndex = if (resolved.binding.source is ImportBindingSource.Seed) {
                        seedScope?.getSlotIndexOf(name)
                    } else {
                        null
                    }
                    val seedSlotFree = seedSlotIndex != null &&
                        modulePlan.slots.values.none { it.index == seedSlotIndex }
                    if (seedSlotFree) {
                        declareSlotNameAt(
                            modulePlan,
                            name,
                            seedSlotIndex,
                            sourceRecord.isMutable,
                            sourceRecord.type == ObjRecord.Type.Delegated
                        )
                    } else {
                        declareSlotNameIn(
                            modulePlan,
                            name,
                            sourceRecord.isMutable,
                            sourceRecord.type == ObjRecord.Type.Delegated
                        )
                    }
                }
                    registerImportBinding(name, resolved.binding, pos)
                    val slot = lookupSlotLocation(name)
                    if (slot != null) {
                        captureLocalRef(name, slot, pos)?.let { ref ->
                            rememberModuleReferencePos(name, pos)
                            resolutionSink?.reference(name, pos)
                            return ref
                        }
                    val ref = if (!useScopeSlots && capturePlanStack.isEmpty() && slot.depth > 0) {
                        LocalSlotRef(
                            name,
                            slot.slot,
                            slot.scopeId,
                            slot.isMutable,
                            slot.isDelegated,
                            pos,
                            strictSlotRefs,
                            captureOwnerScopeId = slot.scopeId,
                            captureOwnerSlot = slot.slot
                        )
                    } else {
                        LocalSlotRef(
                            name,
                            slot.slot,
                            slot.scopeId,
                            slot.isMutable,
                            slot.isDelegated,
                            pos,
                            strictSlotRefs
                        )
                    }
                    rememberModuleReferencePos(name, pos)
                    resolutionSink?.reference(name, pos)
                    return ref
                }
            }
        }
        if (classCtx != null) {
            val implicitType = classCtx.name
            if (hasImplicitThisMember(name, implicitType)) {
                resolutionSink?.referenceMember(name, pos, implicitType)
                val ids = resolveImplicitThisMemberIds(name, pos, implicitType)
                val preferredType = if (currentImplicitThisTypeName() == null) null else implicitType
                return ImplicitThisMemberRef(name, pos, ids.fieldId, ids.methodId, preferredType)
            }
        }
        val implicitType = implicitTypeFromFunc
        if (implicitType != null) {
            resolutionSink?.referenceMember(name, pos, implicitType)
            val ids = resolveImplicitThisMemberIds(name, pos, implicitType)
            val inClassContext = codeContexts.any { ctx -> ctx is CodeContext.ClassBody }
            val currentImplicitType = currentImplicitThisTypeName()
            val preferredType = when {
                inClassContext -> implicitType
                // Extension receiver aliases (extern class name -> host runtime class) can fail strict
                // variant casts; keep current receiver untyped but preserve non-current receivers.
                implicitType == currentImplicitType -> null
                else -> implicitType
            }
            return ImplicitThisMemberRef(name, pos, ids.fieldId, ids.methodId, preferredType)
        }
        if (classCtx != null && classCtx.classScopeMembers.contains(name)) {
            resolutionSink?.referenceMember(name, pos, classCtx.name)
            return ClassScopeMemberRef(name, pos, classCtx.name)
        }
        val classContext = codeContexts.any { ctx -> ctx is CodeContext.ClassBody }
        if (classContext && extensionNames.contains(name)) {
            resolutionSink?.referenceMember(name, pos)
            return LocalVarRef(name, pos)
        }
        resolutionSink?.reference(name, pos)
        if (allowUnresolvedRefs) {
            return LocalVarRef(name, pos)
        }
        throw ScriptError(pos, "unresolved name: $name")
    }

    private fun isRangeType(type: TypeDecl): Boolean {
        val name = when (type) {
            is TypeDecl.Simple -> type.name
            is TypeDecl.Generic -> type.name
            else -> return false
        }
        return name == "Range" ||
            name == "IntRange" ||
            name.endsWith(".Range") ||
            name.endsWith(".IntRange")
    }

    var packageName: String? = null

    class Settings(
        val miniAstSink: MiniAstSink? = null,
        val resolutionSink: ResolutionSink? = null,
        val compileBytecode: Boolean = true,
        val strictSlotRefs: Boolean = true,
        val allowUnresolvedRefs: Boolean = false,
        val seedScope: Scope? = null,
        val useFastLocalRefs: Boolean = false,
    )

    // Optional sink for mini-AST streaming (null by default, zero overhead when not used)
    private val miniSink: MiniAstSink? = settings.miniAstSink
    private val resolutionSink: ResolutionSink? = settings.resolutionSink
    private val compileBytecode: Boolean = settings.compileBytecode
    private val seedScope: Scope? = settings.seedScope
    private val useFastLocalRefs: Boolean = settings.useFastLocalRefs
    private var resolutionScriptDepth = 0
    private val resolutionPredeclared = mutableSetOf<String>()
    private data class ImportedModule(val scope: ModuleScope, val pos: Pos)
    private data class ImportBindingResolution(val binding: ImportBinding, val record: ObjRecord)
    private val importedModules = mutableListOf<ImportedModule>()
    private val importBindings = mutableMapOf<String, ImportBinding>()
    private val enumEntriesByName = mutableMapOf<String, List<String>>()

    // --- Doc-comment collection state (for immediate preceding declarations) ---
    private val pendingDocLines = mutableListOf<String>()
    private var pendingDocStart: Pos? = null
    private var prevWasComment: Boolean = false

    private fun stripCommentLexeme(raw: String): String {
        return when {
            raw.startsWith("//") -> raw.removePrefix("//")
            raw.startsWith("/*") && raw.endsWith("*/") -> {
                val inner = raw.substring(2, raw.length - 2)
                // Trim leading "*" prefixes per line like Javadoc style
                inner.lines().joinToString("\n") { line ->
                    val t = line.trimStart()
                    if (t.startsWith("*")) t.removePrefix("*").trimStart() else line
                }
            }

            else -> raw
        }
    }

    private fun seedResolutionFromScope(scope: Scope, pos: Pos) {
        val sink = resolutionSink ?: return
        var current: Scope? = scope
        while (current != null) {
            for ((name, record) in current.objects) {
                if (!record.visibility.isPublic) continue
                if (!resolutionPredeclared.add(name)) continue
                sink.declareSymbol(name, SymbolKind.LOCAL, record.isMutable, pos)
            }
            current = current.parent
        }
    }

    private fun seedNameObjClassFromScope(scope: Scope) {
        var current: Scope? = scope
        while (current != null) {
            for ((name, record) in current.objects) {
                if (!record.visibility.isPublic) continue
                if (nameObjClass.containsKey(name)) continue
                val declaredClass = record.typeDecl?.let { resolveTypeDeclObjClass(it) }
                if (declaredClass != null) {
                    nameObjClass[name] = declaredClass
                    continue
                }
                val resolved = when (val raw = record.value) {
                    is FrameSlotRef -> raw.peekValue() ?: raw.read()
                    is RecordSlotRef -> raw.peekValue() ?: raw.read()
                    else -> raw
                }
                when (resolved) {
                    is ObjClass -> nameObjClass[name] = resolved
                    is ObjInstance -> nameObjClass[name] = resolved.objClass
                    is ObjDynamic -> nameObjClass[name] = resolved.objClass
                }
            }
            current = current.parent
        }
    }

    private fun seedNameTypeDeclFromScope(scope: Scope) {
        var current: Scope? = scope
        while (current != null) {
            for ((name, record) in current.objects) {
                if (!record.visibility.isPublic) continue
                if (record.typeDecl != null && nameTypeDecl[name] == null) {
                    nameTypeDecl[name] = record.typeDecl
                }
            }
            for ((name, slotIndex) in current.slotNameToIndexSnapshot()) {
                val record = current.getSlotRecord(slotIndex)
                if (record.typeDecl != null && nameTypeDecl[name] == null) {
                    nameTypeDecl[name] = record.typeDecl
                }
            }
            current = current.parent
        }
    }

    private fun resolveImportBinding(name: String, pos: Pos): ImportBindingResolution? {
        val seedRecord = findSeedScopeRecord(name)?.takeIf { it.visibility.isPublic }
        val rootRecord = importManager.rootScope.objects[name]?.takeIf { it.visibility.isPublic }
        val moduleMatches = LinkedHashMap<String, Pair<ImportedModule, ObjRecord>>()
        for (module in importedModules.asReversed()) {
            val found = LinkedHashMap<String, Pair<ModuleScope, ObjRecord>>()
            collectModuleRecordMatches(module.scope, name, mutableSetOf(), found)
            for ((pkg, pair) in found) {
                if (!moduleMatches.containsKey(pkg)) {
                    moduleMatches[pkg] = ImportedModule(pair.first, module.pos) to pair.second
                }
            }
        }
        if (seedRecord != null) {
            seedImportTypeMetadata(name, seedRecord)
            return ImportBindingResolution(ImportBinding(name, ImportBindingSource.Seed), seedRecord)
        }
        if (rootRecord != null) {
            seedImportTypeMetadata(name, rootRecord)
            return ImportBindingResolution(ImportBinding(name, ImportBindingSource.Root), rootRecord)
        }
        if (moduleMatches.isEmpty()) return null
        if (moduleMatches.size > 1) {
            val byOrigin = LinkedHashMap<String, MutableList<Pair<ImportedModule, ObjRecord>>>()
            for ((_, pair) in moduleMatches) {
                val origin = pair.second.importedFrom?.packageName ?: pair.first.scope.packageName
                byOrigin.getOrPut(origin) { mutableListOf() }.add(pair)
            }
            if (byOrigin.size == 1) {
                val origin = byOrigin.keys.first()
                val candidates = byOrigin[origin] ?: mutableListOf()
                val preferred = candidates.firstOrNull { it.first.scope.packageName == origin } ?: candidates.first()
                val binding = ImportBinding(name, ImportBindingSource.Module(origin, preferred.first.pos))
                seedImportTypeMetadata(name, preferred.second)
                return ImportBindingResolution(binding, preferred.second)
            }
            val moduleNames = moduleMatches.keys.toList()
            throw ScriptError(pos, "symbol $name is ambiguous between imports: ${moduleNames.joinToString(", ")}")
        }
        val (module, record) = moduleMatches.values.first()
        val binding = ImportBinding(name, ImportBindingSource.Module(module.scope.packageName, module.pos))
        seedImportTypeMetadata(name, record)
        return ImportBindingResolution(binding, record)
    }

    private fun seedImportTypeMetadata(name: String, record: ObjRecord) {
        if (record.typeDecl != null && nameTypeDecl[name] == null) {
            nameTypeDecl[name] = record.typeDecl
        }
        if (!nameObjClass.containsKey(name)) {
            record.typeDecl?.let { resolveTypeDeclObjClass(it) }?.let {
                nameObjClass[name] = it
                return
            }
            when (val value = record.value) {
                is ObjClass -> nameObjClass[name] = value
                is ObjInstance -> nameObjClass[name] = value.objClass
            }
        }
    }

    private fun collectModuleRecordMatches(
        scope: ModuleScope,
        name: String,
        visited: MutableSet<String>,
        out: MutableMap<String, Pair<ModuleScope, ObjRecord>>
    ) {
        if (!visited.add(scope.packageName)) return
        val record = scope.objects[name]
        if (record != null && record.visibility.isPublic) {
            if (!out.containsKey(scope.packageName)) {
                out[scope.packageName] = scope to record
            }
        }
        for (child in scope.importedModules) {
            collectModuleRecordMatches(child, name, visited, out)
        }
    }

    private fun registerImportBinding(name: String, binding: ImportBinding, pos: Pos) {
        val existing = importBindings[name] ?: run {
            importBindings[name] = binding
            scopeSeedNames.add(name)
            return
        }
        if (!sameImportBinding(existing, binding)) {
            throw ScriptError(pos, "symbol $name resolves to multiple imports")
        }
    }

    private fun sameImportBinding(left: ImportBinding, right: ImportBinding): Boolean {
        if (left.symbol != right.symbol) return false
        val leftSrc = left.source
        val rightSrc = right.source
        return when (leftSrc) {
            is ImportBindingSource.Module -> {
                rightSrc is ImportBindingSource.Module && leftSrc.name == rightSrc.name
            }
            ImportBindingSource.Root -> rightSrc is ImportBindingSource.Root
            ImportBindingSource.Seed -> rightSrc is ImportBindingSource.Seed
        }
    }

    private fun findSeedScopeRecord(name: String): ObjRecord? {
        var current = seedScope
        var hops = 0
        while (current != null && hops++ < 1024) {
            current.objects[name]?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun shouldSeedDefaultStdlib(): Boolean {
        if (seedScope != null) return false
        if (importManager !== Script.defaultImportManager) return false
        val sourceName = cc.tokens.firstOrNull()?.pos?.source?.fileName
        return sourceName != "lyng.stdlib"
    }

    private fun looksLikeExtensionReceiver(): Boolean {
        val saved = cc.savePos()
        try {
            if (cc.peekNextNonWhitespace().type != Token.Type.ID) return false
            cc.nextNonWhitespace()
            // consume qualified name segments
            while (cc.peekNextNonWhitespace().type == Token.Type.DOT) {
                val dotPos = cc.savePos()
                cc.nextNonWhitespace()
                if (cc.peekNextNonWhitespace().type != Token.Type.ID) {
                    cc.restorePos(dotPos)
                    break
                }
                cc.nextNonWhitespace()
                val afterSegment = cc.peekNextNonWhitespace()
                if (afterSegment.type != Token.Type.DOT &&
                    afterSegment.type != Token.Type.LT &&
                    afterSegment.type != Token.Type.QUESTION &&
                    afterSegment.type != Token.Type.IFNULLASSIGN
                ) {
                    cc.restorePos(dotPos)
                    break
                }
            }
            // optional generic arguments
            if (cc.peekNextNonWhitespace().type == Token.Type.LT) {
                var depth = 0
                while (true) {
                    val tok = cc.nextNonWhitespace()
                    when (tok.type) {
                        Token.Type.LT -> depth += 1
                        Token.Type.GT -> {
                            depth -= 1
                            if (depth <= 0) break
                        }
                        Token.Type.SHR -> {
                            depth -= 2
                            if (depth <= 0) break
                        }
                        Token.Type.EOF -> return false
                        else -> {}
                    }
                }
            }
            // nullable suffix
            if (cc.peekNextNonWhitespace().type == Token.Type.QUESTION ||
                cc.peekNextNonWhitespace().type == Token.Type.IFNULLASSIGN
            ) {
                cc.nextNonWhitespace()
            }
            val dotTok = cc.peekNextNonWhitespace()
            if (dotTok.type != Token.Type.DOT) return false
            val savedDot = cc.savePos()
            cc.nextNonWhitespace()
            val nameTok = cc.peekNextNonWhitespace()
            cc.restorePos(savedDot)
            return nameTok.type == Token.Type.ID
        } finally {
            cc.restorePos(saved)
        }
    }

    private fun shouldImplicitTypeVar(name: String, explicit: Set<String>): Boolean {
        if (explicit.contains(name)) return true
        if (name.contains('.')) return false
        if (resolveClassByName(name) != null) return false
        if (resolveTypeDeclObjClass(TypeDecl.Simple(name, false)) != null) return false
        return name.length == 1 || name in setOf("T", "R", "E", "K", "V")
    }

    private fun normalizeReceiverTypeDecl(
        receiver: TypeDecl?,
        explicitTypeParams: Set<String>
    ): Pair<TypeDecl?, Set<String>> {
        if (receiver == null) return null to emptySet()
        val implicit = mutableSetOf<String>()
        fun transform(decl: TypeDecl): TypeDecl = when (decl) {
            is TypeDecl.Simple -> {
                if (shouldImplicitTypeVar(decl.name, explicitTypeParams)) {
                    if (!explicitTypeParams.contains(decl.name)) implicit += decl.name
                    TypeDecl.TypeVar(decl.name, decl.isNullable)
                } else decl
            }
            is TypeDecl.TypeVar -> {
                if (!explicitTypeParams.contains(decl.name)) implicit += decl.name
                decl
            }
            is TypeDecl.Generic -> TypeDecl.Generic(
                decl.name,
                decl.args.map { transform(it) },
                decl.isNullable
            )
            is TypeDecl.Function -> TypeDecl.Function(
                receiver = decl.receiver?.let { transform(it) },
                params = decl.params.map { transform(it) },
                returnType = transform(decl.returnType),
                nullable = decl.isNullable
            )
            is TypeDecl.Ellipsis -> TypeDecl.Ellipsis(transform(decl.elementType), decl.isNullable)
            is TypeDecl.Union -> TypeDecl.Union(decl.options.map { transform(it) }, decl.isNullable)
            is TypeDecl.Intersection -> TypeDecl.Intersection(decl.options.map { transform(it) }, decl.isNullable)
            else -> decl
        }
        return transform(receiver) to implicit
    }

    private var anonCounter = 0
    private fun generateAnonName(pos: Pos): String {
        return "${"$"}${"Anon"}_${pos.line+1}_${pos.column}_${++anonCounter}"
    }

    private fun pushPendingDocToken(t: Token) {
        val s = stripCommentLexeme(t.value)
        if (pendingDocStart == null) pendingDocStart = t.pos
        pendingDocLines += s
        prevWasComment = true
    }

    private fun clearPendingDoc() {
        pendingDocLines.clear()
        pendingDocStart = null
        prevWasComment = false
    }

    private fun consumePendingDoc(): MiniDoc? {
        if (pendingDocLines.isEmpty()) return null
        val start = pendingDocStart ?: cc.currentPos()
        val doc = MiniDoc.parse(MiniRange(start, start), pendingDocLines)
        clearPendingDoc()
        return doc
    }

    private fun nextNonWhitespace(): Token {
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> {
                    pushPendingDocToken(t)
                }

                Token.Type.NEWLINE -> {
                    if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                }

                Token.Type.EOF -> return t
                else -> return t
            }
        }
    }

    // Set just before entering a declaration parse, taken from keyword token position
    private var pendingDeclStart: Pos? = null
    private var pendingDeclDoc: MiniDoc? = null

    private val initStack = mutableListOf<MutableList<Statement>>()

    private data class CompileClassInfo(
        val name: String,
        val packageName: String?,
        val fieldIds: Map<String, Int>,
        val methodIds: Map<String, Int>,
        val nextFieldId: Int,
        val nextMethodId: Int,
        val baseNames: List<String>
    )

    private val compileClassInfos = mutableMapOf<String, CompileClassInfo>()
    private val compileClassStubs = mutableMapOf<String, ObjClass>()

    val currentInitScope: MutableList<Statement>
        get() =
            initStack.lastOrNull() ?: cc.syntaxError("no initialization scope exists here")

    private fun pushInitScope(): MutableList<Statement> = mutableListOf<Statement>().also { initStack.add(it) }

    private fun popInitScope(): MutableList<Statement> = initStack.removeLast()

    private val codeContexts = mutableListOf<CodeContext>(CodeContext.Module(null))

    // Last parsed block range (for Mini-AST function body attachment)
    private var lastParsedBlockRange: MiniRange? = null

    private suspend fun <T> inCodeContext(context: CodeContext, f: suspend () -> T): T {
        codeContexts.add(context)
        pushGenericFunctionScope()
        try {
            val res = f()
            if (context is CodeContext.ClassBody) {
                if (context.pendingInitializations.isNotEmpty()) {
                    val (name, pos) = context.pendingInitializations.entries.first()
                    throw ScriptError(pos, "val '$name' must be initialized in the class body or init block")
                }
            }
            return res
        } finally {
            popGenericFunctionScope()
            codeContexts.removeLast()
        }
    }

    private suspend fun parseScript(): Script {
        val statements = mutableListOf<Statement>()
        val start = cc.currentPos()
        val topLevelSink = if (resolutionScriptDepth == 0) resolutionSink else null
        val atTopLevel = topLevelSink != null
        if (atTopLevel) {
            topLevelSink.enterScope(ScopeKind.MODULE, start, null)
            seedScope?.let { seedResolutionFromScope(it, start) }
            seedResolutionFromScope(importManager.rootScope, start)
        }
        resolutionScriptDepth++
        // Track locals at script level for fast local refs
        val needsSlotPlan = slotPlanStack.isEmpty()
        if (needsSlotPlan) {
            slotPlanStack.add(SlotPlan(mutableMapOf(), 0, nextScopeId++))
            seedScope?.let { scope ->
                seedSlotPlanFromSeedScope(scope)
            }
            val plan = slotPlanStack.last()
            seedScope?.getSlotIndexOf("__PACKAGE__")?.let { slotIndex ->
                declareSlotNameAt(plan, "__PACKAGE__", slotIndex, isMutable = false, isDelegated = false)
            }
            seedScope?.getSlotIndexOf("$~")?.let { slotIndex ->
                declareSlotNameAt(plan, "$~", slotIndex, isMutable = true, isDelegated = false)
            }
            seedScope?.let { seedNameObjClassFromScope(it) }
            seedScope?.let { seedNameTypeDeclFromScope(it) }
            seedNameObjClassFromScope(importManager.rootScope)
            if (shouldSeedDefaultStdlib()) {
                val stdlib = importManager.prepareImport(start, "lyng.stdlib", null)
                seedResolutionFromScope(stdlib, start)
                seedNameObjClassFromScope(stdlib)
                seedSlotPlanFromScope(stdlib)
                importedModules.add(ImportedModule(stdlib, start))
            }
            predeclareTopLevelSymbols()
        }
        return try {
            withLocalNames(emptySet()) {
                // package level declarations
                // Notify sink about script start
                miniSink?.onScriptStart(start)
                do {
                val t = cc.current()
                if (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINGLE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                    when (t.type) {
                        Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> pushPendingDocToken(t)
                        Token.Type.NEWLINE -> {
                            // A standalone newline not immediately following a comment resets doc buffer
                            if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                        }
                    }
                    cc.next()
                    continue
                }
                if (t.type == Token.Type.ID) {
                    when (t.value) {
                        "package" -> {
                            cc.next()
                            val name = loadQualifiedName()
                            if (name.isEmpty()) throw ScriptError(cc.currentPos(), "Expecting package name here")
                            if (packageName != null) throw ScriptError(
                                cc.currentPos(),
                                "package name redefined, already set to $packageName"
                            )
                            packageName = name
                            continue
                        }

                        "import" -> {
                            cc.next()
                            val pos = cc.currentPos()
                            val name = loadQualifiedName()
                            // Emit MiniImport with approximate per-segment ranges
                            run {
                                try {
                                    val parts = name.split('.')
                                    if (parts.isNotEmpty()) {
                                        var col = pos.column
                                        val segs = parts.map { p ->
                                            val start = Pos(pos.source, pos.line, col)
                                            val end = Pos(pos.source, pos.line, col + p.length)
                                            col += p.length + 1 // account for following '.' between segments
                                            MiniImport.Segment(
                                                p,
                                                MiniRange(start, end)
                                            )
                                        }
                                        val lastEnd = segs.last().range.end
                                        miniSink?.onImport(
                                            MiniImport(
                                                MiniRange(pos, lastEnd),
                                                segs
                                            )
                                        )
                                    }
                                } catch (_: Throwable) {
                                    // best-effort; ignore import mini emission failures
                                }
                            }
                            val module = importManager.prepareImport(pos, name, null)
                            importedModules.add(ImportedModule(module, pos))
                            seedResolutionFromScope(module, pos)
                            continue
                        }
                    }
                    // Fast-path: top-level function declarations. Handle here to ensure
                    // Mini-AST emission even if qualifier matcher paths change.
                    if (t.value == "fun" || t.value == "fn") {
                        // Consume the keyword and delegate to the function parser
                        cc.next()
                        pendingDeclStart = t.pos
                        pendingDeclDoc = consumePendingDoc()
                        val st = parseFunctionDeclaration(isExtern = false, isStatic = false)
                        statements += st
                        continue
                    }
                }
                val s = parseStatement(braceMeansLambda = true)?.also {
                    statements += it
                }
                if (s == null) {
                    when (t.type) {
                        Token.Type.RBRACE, Token.Type.EOF, Token.Type.SEMICOLON -> {}
                        else ->
                            throw ScriptError(t.pos, "unexpected `${t.value}` here")
                    }
                    break
                }

                } while (true)
                val modulePlan = if (needsSlotPlan) slotPlanIndices(slotPlanStack.last()) else emptyMap()
                val forcedLocalInfo = if (useScopeSlots) emptyMap() else moduleForcedLocalSlotInfo()
                val forcedLocalScopeId = if (useScopeSlots) null else moduleSlotPlan()?.id
                val allowedScopeNames = if (useScopeSlots) modulePlan.keys else null
                val scopeSlotNameSet = if (useScopeSlots) scopeSeedNames else null
                val moduleScopeId = moduleSlotPlan()?.id
                val isModuleScript = codeContexts.lastOrNull() is CodeContext.Module && resolutionScriptDepth == 1
                val wrapScriptBytecode = compileBytecode && isModuleScript
                val (finalStatements, moduleBytecode) = if (wrapScriptBytecode) {
                    val unwrapped = statements.map { unwrapBytecodeDeep(it) }
                    val block = InlineBlockStatement(unwrapped, start)
                    val bytecodeStmt = BytecodeStatement.wrap(
                        block,
                        "<script>",
                        allowLocalSlots = true,
                        allowedScopeNames = allowedScopeNames,
                        scopeSlotNameSet = scopeSlotNameSet,
                        moduleScopeId = moduleScopeId,
                        forcedLocalSlotInfo = forcedLocalInfo,
                        forcedLocalScopeId = forcedLocalScopeId,
                        slotTypeByScopeId = slotTypeByScopeId,
                        slotTypeDeclByScopeId = slotTypeDeclByScopeId,
                        knownNameObjClass = knownClassMapForBytecode(),
                        knownClassNames = knownClassNamesForBytecode(),
                        knownObjectNames = objectDeclNames,
                        classFieldTypesByName = classFieldTypesByName,
                        enumEntriesByName = enumEntriesByName,
                        callableReturnTypeByScopeId = callableReturnTypeByScopeId,
                        callableReturnTypeByName = callableReturnTypeByName,
                        externBindingNames = externBindingNames,
                        preparedModuleBindingNames = importBindings.keys,
                        scopeRefPosByName = moduleReferencePosByName,
                        lambdaCaptureEntriesByRef = lambdaCaptureEntriesByRef
                    ) as BytecodeStatement
                    unwrapped to bytecodeStmt.bytecodeFunction()
                } else {
                    statements to null
                }
                val moduleRefs = importedModules.map { ImportBindingSource.Module(it.scope.packageName, it.pos) }
                val declaredNames = if (importBindings.isEmpty()) {
                    moduleDeclaredNames.toSet()
                } else {
                    moduleDeclaredNames.subtract(importBindings.keys)
                }
                Script(
                    start,
                    finalStatements,
                    modulePlan,
                    declaredNames,
                    importBindings.toMap(),
                    moduleRefs,
                    moduleBytecode
                )
            }.also {
                // Best-effort script end notification (use current position)
                miniSink?.onScriptEnd(
                    cc.currentPos(),
                    MiniScript(MiniRange(start, cc.currentPos()))
                )
            }
        } finally {
            resolutionScriptDepth--
            if (atTopLevel) {
                topLevelSink.exitScope(cc.currentPos())
            }
            if (needsSlotPlan) {
                slotPlanStack.removeLast()
            }
        }
    }

    fun loadQualifiedName(): String {
        val result = StringBuilder()
        var t = cc.next()
        while (t.type == Token.Type.ID) {
            result.append(t.value)
            t = cc.next()
            if (t.type == Token.Type.DOT) {
                result.append('.')
                t = cc.next()
            }
        }
        cc.previous()
        return result.toString()
    }

    private var lastAnnotation: (suspend (Scope, ObjString, Statement) -> Statement)? = null
    private var isTransientFlag: Boolean = false
    private var lastLabel: String? = null
    private val strictSlotRefs: Boolean = settings.strictSlotRefs
    private val allowUnresolvedRefs: Boolean = settings.allowUnresolvedRefs
    private val returnLabelStack = ArrayDeque<Set<String>>()
    private val rangeParamNamesStack = mutableListOf<Set<String>>()
    private val extensionNames = mutableSetOf<String>()
    private val extensionNamesByType = mutableMapOf<String, MutableSet<String>>()
    private val useScopeSlots: Boolean = seedScope == null

    private fun registerExtensionName(typeName: String, memberName: String) {
        extensionNamesByType.getOrPut(typeName) { mutableSetOf() }.add(memberName)
    }

    private fun hasExtensionFor(typeName: String, memberName: String): Boolean {
        if (extensionNamesByType[typeName]?.contains(memberName) == true) return true
        val scopeRec = seedScope?.get(typeName) ?: importManager.rootScope.get(typeName)
        val cls = (scopeRec?.value as? ObjClass) ?: resolveTypeDeclObjClass(TypeDecl.Simple(typeName, false))
        if (cls != null) {
            for (base in cls.mro) {
                if (extensionNamesByType[base.className]?.contains(memberName) == true) return true
            }
        }
        val candidates = mutableListOf(typeName)
        cls?.mro?.forEach { candidates.add(it.className) }
        for (baseName in candidates) {
            val wrapperNames = listOf(
                extensionCallableName(baseName, memberName),
                extensionPropertyGetterName(baseName, memberName),
                extensionPropertySetterName(baseName, memberName)
            )
            for (wrapperName in wrapperNames) {
                val resolved = resolveImportBinding(wrapperName, Pos.builtIn) ?: continue
                val plan = moduleSlotPlan()
                if (plan != null && !plan.slots.containsKey(wrapperName)) {
                    declareSlotNameIn(
                        plan,
                        wrapperName,
                        resolved.record.isMutable,
                        resolved.record.type == ObjRecord.Type.Delegated
                    )
                }
                registerImportBinding(wrapperName, resolved.binding, Pos.builtIn)
                return true
            }
        }
        return false
    }

    private fun resolveImplicitThisMemberIds(name: String, pos: Pos, implicitTypeName: String?): MemberIds {
        if (implicitTypeName == null) return resolveMemberIds(name, pos, null)
        val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        if (classCtx != null && classCtx.name == implicitTypeName) {
            val fieldId = classCtx.memberFieldIds[name]
            val methodId = classCtx.memberMethodIds[name]
            if (fieldId != null || methodId != null) {
                return MemberIds(fieldId, methodId)
            }
        }
        val info = resolveCompileClassInfo(implicitTypeName)
        val fieldId = info?.fieldIds?.get(name)
        val methodId = info?.methodIds?.get(name)
        if (fieldId == null && methodId == null) {
            val cls = resolveClassByName(implicitTypeName)
            if (cls != null) {
                val fId = cls.instanceFieldIdMap()[name]
                val mId = cls.instanceMethodIdMap(includeAbstract = true)[name]
                if (fId != null || mId != null) return MemberIds(fId, mId)
            }
        }
        if (fieldId == null && methodId == null) {
            if (hasExtensionFor(implicitTypeName, name)) return MemberIds(null, null)
            if (allowUnresolvedRefs) return MemberIds(null, null)
            throw ScriptError(pos, "unknown member $name on $implicitTypeName")
        }
        return MemberIds(fieldId, methodId)
    }

    private fun hasImplicitThisMember(name: String, implicitTypeName: String?): Boolean {
        if (implicitTypeName == null) return false
        val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        if (classCtx != null && classCtx.name == implicitTypeName) {
            if (classCtx.memberFieldIds.containsKey(name) || classCtx.memberMethodIds.containsKey(name)) {
                return true
            }
        }
        val info = resolveCompileClassInfo(implicitTypeName)
        if (info != null && (info.fieldIds.containsKey(name) || info.methodIds.containsKey(name))) return true
        val cls = resolveClassByName(implicitTypeName)
        if (cls != null) {
            if (cls.instanceFieldIdMap().containsKey(name) || cls.instanceMethodIdMap(includeAbstract = true).containsKey(name)) {
                return true
            }
        }
        return hasExtensionFor(implicitTypeName, name)
    }
    private val currentRangeParamNames: Set<String>
        get() = rangeParamNamesStack.lastOrNull() ?: emptySet()
    private val capturePlanStack = mutableListOf<CapturePlan>()
    private var lambdaDepth = 0

    private data class CapturePlan(
        val slotPlan: SlotPlan,
        val isFunction: Boolean,
        val propagateToParentFunction: Boolean,
        val captures: MutableList<CaptureSlot> = mutableListOf(),
        val captureMap: MutableMap<String, CaptureSlot> = mutableMapOf(),
        val captureOwners: MutableMap<String, SlotLocation> = mutableMapOf()
    )

    private fun recordCaptureSlot(name: String, slotLoc: SlotLocation) {
        val plan = capturePlanStack.lastOrNull() ?: return
        recordCaptureSlotInto(plan, name, slotLoc)
        if (plan.propagateToParentFunction) {
            for (i in capturePlanStack.size - 2 downTo 0) {
                val parent = capturePlanStack[i]
                if (parent.isFunction) {
                    recordCaptureSlotInto(parent, name, slotLoc)
                    break
                }
            }
        }
    }

    private fun recordCaptureSlotInto(plan: CapturePlan, name: String, slotLoc: SlotLocation) {
        if (plan.captureMap.containsKey(name)) return
        val capture = CaptureSlot(
            name = name,
            ownerScopeId = slotLoc.scopeId,
            ownerSlot = slotLoc.slot,
        )
        plan.captureMap[name] = capture
        plan.captureOwners[name] = slotLoc
        plan.captures += capture
        if (!plan.slotPlan.slots.containsKey(name)) {
            plan.slotPlan.slots[name] = SlotEntry(
                plan.slotPlan.nextIndex,
                isMutable = slotLoc.isMutable,
                isDelegated = slotLoc.isDelegated
            )
            plan.slotPlan.nextIndex += 1
        }
    }

    private fun captureLocalRef(name: String, slotLoc: SlotLocation, pos: Pos): LocalSlotRef? {
        if (capturePlanStack.isEmpty() || slotLoc.depth == 0) return null
        val functionPlan = capturePlanStack.asReversed().firstOrNull { it.isFunction } ?: return null
        val functionIndex = slotPlanStack.indexOfLast { it.id == functionPlan.slotPlan.id }
        val scopeIndex = slotPlanStack.indexOfLast { it.id == slotLoc.scopeId }
        if (functionIndex >= 0 && scopeIndex >= functionIndex) return null
        val modulePlan = moduleSlotPlan()
        if (modulePlan != null && slotLoc.scopeId == modulePlan.id && externBindingNames.contains(name)) {
            return null
        }
        if (useScopeSlots && modulePlan != null && slotLoc.scopeId == modulePlan.id) {
            return null
        }
        if (scopeSeedNames.contains(name)) {
            val isModuleSlot = modulePlan != null && slotLoc.scopeId == modulePlan.id
            if (!isModuleSlot || useScopeSlots) return null
        }
        recordCaptureSlot(name, slotLoc)
        val plan = capturePlanStack.lastOrNull() ?: return null
        val entry = plan.slotPlan.slots[name] ?: return null
        return LocalSlotRef(
            name,
            entry.index,
            plan.slotPlan.id,
            entry.isMutable,
            entry.isDelegated,
            pos,
            strictSlotRefs,
            captureOwnerScopeId = slotLoc.scopeId,
            captureOwnerSlot = slotLoc.slot
        )
    }

    private fun captureSlotRef(name: String, pos: Pos): ObjRef? {
        if (capturePlanStack.isEmpty()) return null
        if (name == "this") return null
        val slotLoc = lookupSlotLocation(name) ?: return null
        captureLocalRef(name, slotLoc, pos)?.let { return it }
        if (slotLoc.depth > 0) {
            recordCaptureSlot(name, slotLoc)
        }
        return LocalSlotRef(
            name,
            slotLoc.slot,
            slotLoc.scopeId,
            slotLoc.isMutable,
            slotLoc.isDelegated,
            pos,
            strictSlotRefs
        )
    }

    private fun knownClassMapForBytecode(): Map<String, ObjClass> {
        val result = LinkedHashMap<String, ObjClass>()
        fun addScope(scope: Scope?) {
            if (scope == null) return
            for ((name, rec) in scope.objects) {
                val cls = rec.value as? ObjClass
                if (cls != null) {
                    if (!result.containsKey(name)) result[name] = cls
                    continue
                }
                val obj = rec.value as? ObjInstance ?: continue
                if (!result.containsKey(name)) result[name] = obj.objClass
            }
        }
        addScope(seedScope)
        addScope(importManager.rootScope)
        for (module in importedModules) {
            addScope(module.scope)
        }
        for (name in compileClassInfos.keys) {
            val cls = resolveClassByName(name) ?: continue
            if (!result.containsKey(name)) result[name] = cls
        }
        return result
    }

    private fun knownClassNamesForBytecode(): Set<String> {
        val result = LinkedHashSet<String>()
        fun addScope(scope: Scope?) {
            if (scope == null) return
            for ((name, rec) in scope.objects) {
                if (rec.value is ObjClass) result.add(name)
            }
        }
        addScope(seedScope)
        addScope(importManager.rootScope)
        for (module in importedModules) {
            addScope(module.scope)
        }
        result.addAll(compileClassInfos.keys)
        return result
    }

    private fun wrapBytecode(stmt: Statement): Statement {
        if (codeContexts.lastOrNull() is CodeContext.Module) return stmt
        if (codeContexts.lastOrNull() is CodeContext.ClassBody) return stmt
        if (codeContexts.any { it is CodeContext.Function }) return stmt
        if (stmt is FunctionDeclStatement ||
            stmt is ClassDeclStatement ||
            stmt is EnumDeclStatement
        ) return stmt
        val allowLocals = codeContexts.lastOrNull() !is CodeContext.ClassBody
        val returnLabels = returnLabelStack.lastOrNull() ?: emptySet()
        val modulePlan = moduleSlotPlan()
        val allowedScopeNames = if (useScopeSlots) modulePlan?.slots?.keys else null
        val scopeSlotNameSet = if (useScopeSlots) scopeSeedNames else null
        val moduleScopeId = modulePlan?.id
        return BytecodeStatement.wrap(
            stmt,
            "stmt@${stmt.pos}",
            allowLocalSlots = allowLocals,
            returnLabels = returnLabels,
            rangeLocalNames = currentRangeParamNames,
            allowedScopeNames = allowedScopeNames,
            scopeSlotNameSet = scopeSlotNameSet,
            moduleScopeId = moduleScopeId,
            slotTypeByScopeId = slotTypeByScopeId,
            slotTypeDeclByScopeId = slotTypeDeclByScopeId,
            knownNameObjClass = knownClassMapForBytecode(),
            knownClassNames = knownClassNamesForBytecode(),
            knownObjectNames = objectDeclNames,
            classFieldTypesByName = classFieldTypesByName,
            enumEntriesByName = enumEntriesByName,
            callableReturnTypeByScopeId = callableReturnTypeByScopeId,
            callableReturnTypeByName = callableReturnTypeByName,
            externCallableNames = externCallableNames,
            externBindingNames = externBindingNames,
            preparedModuleBindingNames = importBindings.keys,
            scopeRefPosByName = moduleReferencePosByName,
            lambdaCaptureEntriesByRef = lambdaCaptureEntriesByRef
        )
    }

    private fun wrapClassBodyBytecode(stmt: Statement, name: String): Statement {
        val target = if (stmt is Script) InlineBlockStatement(stmt.statements(), stmt.pos) else stmt
        val returnLabels = returnLabelStack.lastOrNull() ?: emptySet()
        val modulePlan = moduleSlotPlan()
        val allowedScopeNames = if (useScopeSlots) modulePlan?.slots?.keys else null
        val scopeSlotNameSet = if (useScopeSlots) scopeSeedNames else null
        val moduleScopeId = modulePlan?.id
        return BytecodeStatement.wrap(
            target,
            name,
            allowLocalSlots = true,
            returnLabels = returnLabels,
            rangeLocalNames = currentRangeParamNames,
            allowedScopeNames = allowedScopeNames,
            scopeSlotNameSet = scopeSlotNameSet,
            moduleScopeId = moduleScopeId,
            slotTypeByScopeId = slotTypeByScopeId,
            slotTypeDeclByScopeId = slotTypeDeclByScopeId,
            knownNameObjClass = knownClassMapForBytecode(),
            knownClassNames = knownClassNamesForBytecode(),
            knownObjectNames = objectDeclNames,
            classFieldTypesByName = classFieldTypesByName,
            enumEntriesByName = enumEntriesByName,
            callableReturnTypeByScopeId = callableReturnTypeByScopeId,
            callableReturnTypeByName = callableReturnTypeByName,
            externCallableNames = externCallableNames,
            externBindingNames = externBindingNames,
            preparedModuleBindingNames = importBindings.keys,
            scopeRefPosByName = moduleReferencePosByName,
            lambdaCaptureEntriesByRef = lambdaCaptureEntriesByRef
        )
    }

    private fun wrapInstanceInitBytecode(stmt: Statement, name: String): BytecodeStatement {
        val wrapped = wrapFunctionBytecode(stmt, name)
        return (wrapped as? BytecodeStatement)
            ?: throw ScriptError(stmt.pos, "non-bytecode init statement: $name")
    }

    private fun wrapFunctionBytecode(
        stmt: Statement,
        name: String,
        extraKnownNameObjClass: Map<String, ObjClass> = emptyMap(),
        forcedLocalSlots: Map<String, Int> = emptyMap(),
        forcedLocalScopeId: Int? = null
    ): Statement {
        val returnLabels = returnLabelStack.lastOrNull() ?: emptySet()
        val modulePlan = moduleSlotPlan()
        val allowedScopeNames = if (useScopeSlots) modulePlan?.slots?.keys else null
        val scopeSlotNameSet = if (useScopeSlots) scopeSeedNames else null
        val moduleScopeId = modulePlan?.id
        val globalSlotInfo = if (useScopeSlots) emptyMap() else moduleForcedLocalSlotInfo()
        val globalSlotScopeId = if (useScopeSlots) null else moduleScopeId
        val knownNames = if (extraKnownNameObjClass.isEmpty()) {
            knownClassMapForBytecode()
        } else {
            val merged = LinkedHashMap<String, ObjClass>()
            merged.putAll(knownClassMapForBytecode())
            merged.putAll(extraKnownNameObjClass)
            merged
        }
        return BytecodeStatement.wrap(
            stmt,
            "fn@$name",
            allowLocalSlots = true,
            returnLabels = returnLabels,
            rangeLocalNames = currentRangeParamNames,
            allowedScopeNames = allowedScopeNames,
            scopeSlotNameSet = scopeSlotNameSet,
            moduleScopeId = moduleScopeId,
            forcedLocalSlots = forcedLocalSlots,
            forcedLocalScopeId = forcedLocalScopeId,
            globalSlotInfo = globalSlotInfo,
            globalSlotScopeId = globalSlotScopeId,
            slotTypeByScopeId = slotTypeByScopeId,
            slotTypeDeclByScopeId = slotTypeDeclByScopeId,
            knownNameObjClass = knownNames,
            knownClassNames = knownClassNamesForBytecode(),
            knownObjectNames = objectDeclNames,
            classFieldTypesByName = classFieldTypesByName,
            enumEntriesByName = enumEntriesByName,
            callableReturnTypeByScopeId = callableReturnTypeByScopeId,
            callableReturnTypeByName = callableReturnTypeByName,
            externCallableNames = externCallableNames,
            externBindingNames = externBindingNames,
            preparedModuleBindingNames = importBindings.keys,
            scopeRefPosByName = moduleReferencePosByName,
            lambdaCaptureEntriesByRef = lambdaCaptureEntriesByRef
        )
    }

    private fun wrapDefaultArgsBytecode(
        args: ArgsDeclaration,
        forcedLocalSlots: Map<String, Int>,
        forcedLocalScopeId: Int
    ): ArgsDeclaration {
        if (!compileBytecode) return args
        if (args.params.none { it.defaultValue is Statement }) return args
        val updated = args.params.map { param ->
            val defaultValue = param.defaultValue
            val stmt = defaultValue as? Statement
            if (stmt == null) return@map param
            val bytecode = when (stmt) {
                is BytecodeStatement -> stmt
                is BytecodeBodyProvider -> stmt.bytecodeBody()
                else -> null
            }
            if (bytecode != null) {
                param
            } else {
                val wrapped = wrapFunctionBytecode(
                    stmt,
                    "argDefault@${param.name}",
                    forcedLocalSlots = forcedLocalSlots,
                    forcedLocalScopeId = forcedLocalScopeId
                )
                param.copy(defaultValue = wrapped)
            }
        }
        return if (updated == args.params) args else args.copy(params = updated)
    }

    private fun wrapParsedArgsBytecode(
        args: List<ParsedArgument>?,
        forcedLocalSlots: Map<String, Int> = emptyMap(),
        forcedLocalScopeId: Int? = null
    ): List<ParsedArgument>? {
        if (!compileBytecode || args == null || args.isEmpty()) return args
        var changed = false
        val updated = args.mapIndexed { index, arg ->
            val stmt = arg.value as? Statement ?: return@mapIndexed arg
            val bytecode = when (stmt) {
                is BytecodeStatement -> stmt
                is BytecodeBodyProvider -> stmt.bytecodeBody()
                else -> null
            }
            if (bytecode != null) return@mapIndexed arg
            val wrapped = wrapFunctionBytecode(
                stmt,
                "arg@${index}",
                forcedLocalSlots = forcedLocalSlots,
                forcedLocalScopeId = forcedLocalScopeId
            )
            changed = true
            arg.copy(value = wrapped)
        }
        return if (changed) updated else args
    }

    private fun containsDelegatedRefs(stmt: Statement): Boolean {
        val target = if (stmt is BytecodeStatement) stmt.original else stmt
        return when (target) {
            is ExpressionStatement -> containsDelegatedRefs(target.ref)
            is BlockStatement -> target.statements().any { containsDelegatedRefs(it) }
            is VarDeclStatement -> target.initializer?.let { containsDelegatedRefs(it) } ?: false
            is DelegatedVarDeclStatement -> containsDelegatedRefs(target.initializer)
            is DestructuringVarDeclStatement -> containsDelegatedRefs(target.initializer)
            is IfStatement -> {
                containsDelegatedRefs(target.condition) ||
                    containsDelegatedRefs(target.ifBody) ||
                    (target.elseBody?.let { containsDelegatedRefs(it) } ?: false)
            }
            is ForInStatement -> {
                containsDelegatedRefs(target.source) ||
                    containsDelegatedRefs(target.body) ||
                    (target.elseStatement?.let { containsDelegatedRefs(it) } ?: false)
            }
            is WhileStatement -> {
                containsDelegatedRefs(target.condition) ||
                    containsDelegatedRefs(target.body) ||
                    (target.elseStatement?.let { containsDelegatedRefs(it) } ?: false)
            }
            is DoWhileStatement -> {
                containsDelegatedRefs(target.body) ||
                    containsDelegatedRefs(target.condition) ||
                    (target.elseStatement?.let { containsDelegatedRefs(it) } ?: false)
            }
            is WhenStatement -> {
                containsDelegatedRefs(target.value) ||
                    target.cases.any { case ->
                        case.conditions.any { cond ->
                            when (cond) {
                                is WhenEqualsCondition -> containsDelegatedRefs(cond.expr)
                                is WhenInCondition -> containsDelegatedRefs(cond.expr)
                                is WhenIsCondition -> false
                                is WhenNullableCondition -> false
                            }
                        } || containsDelegatedRefs(case.block)
                    } ||
                    (target.elseCase?.let { containsDelegatedRefs(it) } ?: false)
            }
            is ReturnStatement -> target.resultExpr?.let { containsDelegatedRefs(it) } ?: false
            is BreakStatement -> target.resultExpr?.let { containsDelegatedRefs(it) } ?: false
            is ThrowStatement -> containsDelegatedRefs(target.throwExpr)
            else -> false
        }
    }

    private fun containsDelegatedRefs(ref: ObjRef): Boolean {
        return when (ref) {
            is LocalSlotRef -> ref.isDelegated
            is BinaryOpRef -> containsDelegatedRefs(ref.left) || containsDelegatedRefs(ref.right)
            is UnaryOpRef -> containsDelegatedRefs(ref.a)
            is CastRef -> containsDelegatedRefs(ref.castValueRef()) || containsDelegatedRefs(ref.castTypeRef())
            is net.sergeych.lyng.obj.TypeDeclRef -> false
            is AssignRef -> {
                val target = ref.target as? LocalSlotRef
                (target?.isDelegated == true) || containsDelegatedRefs(ref.value)
            }
            is AssignOpRef -> containsDelegatedRefs(ref.target) || containsDelegatedRefs(ref.value)
            is AssignIfNullRef -> containsDelegatedRefs(ref.target) || containsDelegatedRefs(ref.value)
            is IncDecRef -> containsDelegatedRefs(ref.target)
            is ConditionalRef ->
                containsDelegatedRefs(ref.condition) || containsDelegatedRefs(ref.ifTrue) || containsDelegatedRefs(ref.ifFalse)
            is ElvisRef -> containsDelegatedRefs(ref.left) || containsDelegatedRefs(ref.right)
            is FieldRef -> containsDelegatedRefs(ref.target)
            is IndexRef -> containsDelegatedRefs(ref.targetRef) || containsDelegatedRefs(ref.indexRef)
            is ListLiteralRef -> ref.entries().any {
                when (it) {
                    is ListEntry.Element -> containsDelegatedRefs(it.ref)
                    is ListEntry.Spread -> containsDelegatedRefs(it.ref)
                }
            }
            is MapLiteralRef -> ref.entries().any {
                when (it) {
                    is net.sergeych.lyng.obj.MapLiteralEntry.Named -> {
                        containsDelegatedRefs(it.value)
                    }
                    is net.sergeych.lyng.obj.MapLiteralEntry.Spread -> containsDelegatedRefs(it.ref)
                }
            }
            is CallRef -> containsDelegatedRefs(ref.target) || ref.args.any {
                val v = it.value
                when (v) {
                    is Statement -> containsDelegatedRefs(v)
                    is net.sergeych.lyng.obj.ObjRef -> containsDelegatedRefs(v)
                    else -> false
                }
            }
            is MethodCallRef -> containsDelegatedRefs(ref.receiver) || ref.args.any {
                val v = it.value
                when (v) {
                    is Statement -> containsDelegatedRefs(v)
                    is net.sergeych.lyng.obj.ObjRef -> containsDelegatedRefs(v)
                    else -> false
                }
            }
            is StatementRef -> containsDelegatedRefs(ref.statement)
            else -> false
        }
    }

    private fun unwrapBytecodeDeep(stmt: Statement): Statement {
        return when (stmt) {
            is BytecodeStatement -> unwrapBytecodeDeep(stmt.original)
            is BlockStatement -> {
                val unwrapped = stmt.statements().map { unwrapBytecodeDeep(it) }
                val script = Script(stmt.block.pos, unwrapped)
                BlockStatement(script, stmt.slotPlan, stmt.scopeId, stmt.captureSlots, stmt.pos)
            }
            is InlineBlockStatement -> {
                val unwrapped = stmt.statements().map { unwrapBytecodeDeep(it) }
                InlineBlockStatement(unwrapped, stmt.pos)
            }
            is VarDeclStatement -> {
                val init = stmt.initializer?.let { unwrapBytecodeDeep(it) }
                VarDeclStatement(
                    stmt.name,
                    stmt.isMutable,
                    stmt.visibility,
                    stmt.actualExtern,
                    init,
                    stmt.isTransient,
                    stmt.typeDecl,
                    stmt.slotIndex,
                    stmt.scopeId,
                    stmt.pos,
                    stmt.initializerObjClass
                )
            }
            is IfStatement -> {
                val cond = unwrapBytecodeDeep(stmt.condition)
                val ifBody = unwrapBytecodeDeep(stmt.ifBody)
                val elseBody = stmt.elseBody?.let { unwrapBytecodeDeep(it) }
                IfStatement(cond, ifBody, elseBody, stmt.pos)
            }
            is ForInStatement -> {
                val source = unwrapBytecodeDeep(stmt.source)
                val body = unwrapBytecodeDeep(stmt.body)
                val elseBody = stmt.elseStatement?.let { unwrapBytecodeDeep(it) }
                ForInStatement(
                    stmt.loopVarName,
                    source,
                    stmt.constRange,
                    body,
                    elseBody,
                    stmt.label,
                    stmt.canBreak,
                    stmt.loopSlotPlan,
                    stmt.loopScopeId,
                    stmt.pos
                )
            }
            is WhileStatement -> {
                val condition = unwrapBytecodeDeep(stmt.condition)
                val body = unwrapBytecodeDeep(stmt.body)
                val elseBody = stmt.elseStatement?.let { unwrapBytecodeDeep(it) }
                WhileStatement(
                    condition,
                    body,
                    elseBody,
                    stmt.label,
                    stmt.canBreak,
                    stmt.loopSlotPlan,
                    stmt.pos
                )
            }
            is DoWhileStatement -> {
                val body = unwrapBytecodeDeep(stmt.body)
                val condition = unwrapBytecodeDeep(stmt.condition)
                val elseBody = stmt.elseStatement?.let { unwrapBytecodeDeep(it) }
                DoWhileStatement(
                    body,
                    condition,
                    elseBody,
                    stmt.label,
                    stmt.loopSlotPlan,
                    stmt.pos
                )
            }
            is BreakStatement -> {
                val resultExpr = stmt.resultExpr?.let { unwrapBytecodeDeep(it) }
                BreakStatement(stmt.label, resultExpr, stmt.pos)
            }
            is ContinueStatement -> ContinueStatement(stmt.label, stmt.pos)
            is ReturnStatement -> {
                val resultExpr = stmt.resultExpr?.let { unwrapBytecodeDeep(it) }
                ReturnStatement(stmt.label, resultExpr, stmt.pos)
            }
            is ThrowStatement -> ThrowStatement(unwrapBytecodeDeep(stmt.throwExpr), stmt.pos)
            else -> stmt
        }
    }

    private suspend fun parseStatement(braceMeansLambda: Boolean = false): Statement? {
        lastAnnotation = null
        lastLabel = null
        isTransientFlag = false
        while (true) {
            val t = cc.next()
            return when (t.type) {
                Token.Type.ID, Token.Type.OBJECT -> {
                    parseKeywordStatement(t)?.let { wrapBytecode(it) }
                        ?: run {
                            cc.previous()
                            parseExpressionWithContracts()
                        }
                }

                Token.Type.PLUS2, Token.Type.MINUS2 -> {
                    cc.previous()
                    parseExpressionWithContracts()
                }

                Token.Type.ATLABEL -> {
                    val label = t.value
                    if (label == "Transient") {
                        isTransientFlag = true
                        continue
                    }
                    if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        lastLabel = label
                    }
                    lastAnnotation = parseAnnotation(t)
                    continue
                }

                Token.Type.LABEL -> continue
                Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> {
                    pushPendingDocToken(t)
                    continue
                }

                Token.Type.NEWLINE -> {
                    if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                    continue
                }

                Token.Type.SEMICOLON -> continue

                Token.Type.LBRACE -> {
                    cc.previous()
                    if (braceMeansLambda)
                        parseExpressionWithContracts()
                    else
                        wrapBytecode(parseBlock())
                }

                Token.Type.RBRACE, Token.Type.RBRACKET -> {
                    cc.previous()
                    return null
                }

                Token.Type.EOF -> null

                else -> {
                    // could be expression
                    cc.previous()
                    parseExpressionWithContracts()
                }
            }
        }
    }

    private suspend fun parseExpressionWithContracts(): Statement? {
        val expression = parseExpression() ?: return null
        applyAssertSmartCasts(expression)
        return wrapBytecode(expression)
    }

    private suspend fun parseExpression(): Statement? {
        val pos = cc.currentPos()
        return parseExpressionLevel()?.let { ref ->
            ExpressionStatement(ref, pos)
        }
    }

    private suspend fun parseExpressionLevel(level: Int = 0): ObjRef? {
        if (level == lastLevel)
            return parseTerm()
        var lvalue: ObjRef? = parseExpressionLevel(level + 1) ?: return null

        while (true) {
            val opToken = cc.next()
            val op = byLevel[level][opToken.type]
            if (op == null) {
                // handle ternary conditional at the top precedence level only: a ? b : c
                if (opToken.type == Token.Type.QUESTION && level == 0) {
                    val thenRef = parseExpressionLevel(level + 1)
                        ?: throw ScriptError(opToken.pos, "Expecting expression after '?'")
                    val colon = cc.next()
                    if (colon.type != Token.Type.COLON) colon.raiseSyntax("missing ':'")
                    val elseRef = parseExpressionLevel(level + 1)
                        ?: throw ScriptError(colon.pos, "Expecting expression after ':'")
                    lvalue = ConditionalRef(lvalue!!, thenRef, elseRef)
                    continue
                }
                cc.previous()
                break
            }

            val res = if (opToken.type == Token.Type.AS || opToken.type == Token.Type.ASNULL) {
                val (typeDecl, _) = parseTypeExpressionWithMini()
                val typeRef = typeDeclToTypeRef(typeDecl, opToken.pos)
                if (opToken.type == Token.Type.AS) {
                    CastRef(lvalue!!, typeRef, false, opToken.pos)
                } else {
                    CastRef(lvalue!!, typeRef, true, opToken.pos)
                }
            } else if (opToken.type == Token.Type.IS || opToken.type == Token.Type.NOTIS) {
                val nullablePredicate = tryConsumeNullablePredicate()
                if (nullablePredicate) {
                    val nullRef = ConstRef(ObjNull.asReadonly)
                    if (opToken.type == Token.Type.IS) {
                        BinaryOpRef(BinOp.IS, nullRef, lvalue!!)
                    } else {
                        BinaryOpRef(BinOp.NOTIS, nullRef, lvalue!!)
                    }
                } else {
                    val (typeDecl, _) = parseTypeExpressionWithMini()
                    val typeRef = net.sergeych.lyng.obj.TypeDeclRef(typeDecl, opToken.pos)
                    if (opToken.type == Token.Type.IS) {
                        BinaryOpRef(BinOp.IS, lvalue!!, typeRef)
                    } else {
                        BinaryOpRef(BinOp.NOTIS, lvalue!!, typeRef)
                    }
                }
            } else {
                val rvalue = parseExpressionLevel(level + 1)
                    ?: throw ScriptError(opToken.pos, "Expecting expression")
                if (opToken.type == Token.Type.PLUSASSIGN) {
                    checkCollectionPlusAssignTypes(lvalue!!, rvalue, opToken.pos)
                }
                op.generate(opToken.pos, lvalue!!, rvalue)
            }
            if (opToken.type == Token.Type.ASSIGN) {
                val ctx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
                if (ctx != null) {
                    val target = lvalue
                    val name = when (target) {
                        is LocalVarRef -> target.name
                        is FastLocalVarRef -> target.name
                        is LocalSlotRef -> target.name
                        is ImplicitThisMemberRef -> target.name
                        is ThisFieldSlotRef -> target.name
                        is QualifiedThisFieldSlotRef -> target.name
                        is FieldRef -> if (target.target is LocalVarRef && target.target.name == "this") target.name else null
                        else -> null
                    }
                    if (name != null) ctx.pendingInitializations.remove(name)
                }
            }
            lvalue = res
        }
        return lvalue
    }

    private fun tryConsumeNullablePredicate(): Boolean {
        val save = cc.savePos()
        val t = cc.nextNonWhitespace()
        return if (t.type == Token.Type.ID && t.value == "nullable") {
            true
        } else {
            cc.restorePos(save)
            false
        }
    }

    private suspend fun parseTerm(): ObjRef? {
        var operand: ObjRef? = null
        var pendingCallTypeArgs: List<TypeDecl>? = null

        // newlines _before_
        cc.skipWsTokens()

        while (true) {
            val t = cc.next()
            val startPos = t.pos
            when (t.type) {
//                Token.Type.NEWLINE, Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT-> {
//                    continue
//                }

                // very special case chained calls: call()<NL>.call2 {}.call3()
                Token.Type.NEWLINE -> {
                    val saved = cc.savePos()
                    if (cc.peekNextNonWhitespace().type == Token.Type.DOT) {
                        // chained call continue from it
                        continue
                    } else {
                        // restore position and stop parsing as a term:
                        cc.restorePos(saved)
                        cc.previous()
                        return operand
                    }
                }

                Token.Type.SEMICOLON, Token.Type.EOF, Token.Type.RBRACE, Token.Type.COMMA -> {
                    cc.previous()
                    return operand
                }

                Token.Type.NOT -> {
                    if (operand != null) {
                        val save = cc.savePos()
                        val next = cc.next()
                        if (next.type == Token.Type.NOT) {
                            val operandRef = operand
                            val receiverClass = resolveReceiverClassForMember(operandRef)
                            val inferredType = resolveReceiverTypeDecl(operandRef)
                                ?: receiverClass?.let { TypeDecl.Simple(it.className, false) }
                            if (inferredType == null) {
                                operand = operandRef
                                continue
                            }
                            if (inferredType == TypeDecl.TypeAny || inferredType == TypeDecl.TypeNullableAny) {
                                operand = operandRef
                                continue
                            }
                            val nonNullType = makeTypeDeclNonNullable(inferredType)
                            operand = CastRef(
                                operandRef,
                                TypeDeclRef(nonNullType, t.pos),
                                isNullable = false,
                                atPos = t.pos
                            )
                            continue
                        }
                        cc.restorePos(save)
                        throw ScriptError(t.pos, "unexpected operator not '!' ")
                    }
                    val op = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                    operand = UnaryOpRef(UnaryOp.NOT, op)
                }

                Token.Type.BITNOT -> {
                    if (operand != null) throw ScriptError(t.pos, "unexpected operator '~'")
                    val op = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression after '~'")
                    operand = UnaryOpRef(UnaryOp.BITNOT, op)
                }

                Token.Type.DOT, Token.Type.NULL_COALESCE -> {
                    val isOptional = t.type == Token.Type.NULL_COALESCE
                    operand?.let { left ->
                        // dot call: calling method on the operand, if next is ID, "("
                        var isCall = false
                        val next = cc.next()
                        if (next.type == Token.Type.ID) {
                            // could be () call or obj.method {} call
                            val nt = cc.current()
                            when (nt.type) {
                                Token.Type.LPAREN -> {
                                    cc.next()
                                    if (shouldTreatAsClassScopeCall(left, next.value)) {
                                        val parsed = parseArgs(null)
                                        val args = parsed.first
                                        val tailBlock = parsed.second
                                        isCall = true
                                        val receiverClass = resolveReceiverClassForMember(left)
                                        val nestedClass = receiverClass?.let { resolveClassByName("${it.className}.${next.value}") }
                                        if (nestedClass != null) {
                                            val field = FieldRef(left, next.value, isOptional)
                                            operand = CallRef(field, args, tailBlock, isOptional)
                                        } else {
                                            operand = MethodCallRef(left, next.value, args, tailBlock, isOptional)
                                        }
                                    } else {
                                        // instance method call
                                        val receiverType = if (next.value == "apply" || next.value == "run") {
                                            inferReceiverTypeFromRef(left)
                                        } else null
                                        val parsed = parseArgs(receiverType)
                                        val args = parsed.first
                                        val tailBlock = parsed.second
                                        if (left is LocalVarRef && left.name == "scope") {
                                            val first = args.firstOrNull()?.value
                                            val const = (first as? ExpressionStatement)?.ref as? ConstRef
                                            val name = const?.constValue as? ObjString
                                            if (name != null) {
                                                resolutionSink?.referenceReflection(name.value, next.pos)
                                            }
                                        }
                                        isCall = true
                                        operand = when (left) {
                                            is LocalVarRef -> if (left.name == "this") {
                                                resolutionSink?.referenceMember(next.value, next.pos)
                                                val implicitType = currentImplicitThisTypeName()
                                                val ids = resolveMemberIds(next.value, next.pos, implicitType)
                                                ThisMethodSlotCallRef(next.value, ids.methodId, args, tailBlock, isOptional)
                                            } else if (left.name == "scope") {
                                                if (next.value == "get" || next.value == "set") {
                                                    val first = args.firstOrNull()?.value
                                                    val const = (first as? ExpressionStatement)?.ref as? ConstRef
                                                    val name = const?.constValue as? ObjString
                                                    if (name != null) {
                                                        resolutionSink?.referenceReflection(name.value, next.pos)
                                                    }
                                                }
                                                MethodCallRef(left, next.value, args, tailBlock, isOptional)
                                            } else {
                                                val unionCall = buildUnionMethodCall(left, next.value, next.pos, isOptional, args, tailBlock)
                                                if (unionCall != null) {
                                                    unionCall
                                                } else {
                                                    enforceReceiverTypeForMember(left, next.value, next.pos)
                                                    MethodCallRef(left, next.value, args, tailBlock, isOptional)
                                                }
                                            }
                                            is QualifiedThisRef ->
                                                QualifiedThisMethodSlotCallRef(
                                                    left.typeName,
                                                    next.value,
                                                    resolveMemberIds(next.value, next.pos, left.typeName).methodId,
                                                    args,
                                                    tailBlock,
                                                    isOptional
                                                ).also {
                                                    resolutionSink?.referenceMember(next.value, next.pos, left.typeName)
                                                }
                                            else -> {
                                                val unionCall = buildUnionMethodCall(left, next.value, next.pos, isOptional, args, tailBlock)
                                                if (unionCall != null) {
                                                    unionCall
                                                } else {
                                                    enforceReceiverTypeForMember(left, next.value, next.pos)
                                                    MethodCallRef(left, next.value, args, tailBlock, isOptional)
                                                }
                                            }
                                        }
                                    }
                                }


                                Token.Type.LBRACE, Token.Type.NULL_COALESCE_BLOCKINVOKE -> {
                                    // single lambda arg, like assertThrows { ... }
                                    cc.next()
                                    isCall = true
                                    val receiverType = if (next.value == "apply" || next.value == "run") {
                                        inferReceiverTypeFromRef(left)
                                    } else null
                                    val itType = if (next.value == "let" || next.value == "also") {
                                        inferReceiverTypeFromRef(left)
                                    } else null
                                    val lambda = parseLambdaExpression(receiverType, implicitItType = itType)
                                    val argPos = next.pos
                                    val args = listOf(ParsedArgument(ExpressionStatement(lambda, argPos), next.pos))
                                    operand = when (left) {
                                        is LocalVarRef -> if (left.name == "this") {
                                            resolutionSink?.referenceMember(next.value, next.pos)
                                            val implicitType = currentImplicitThisTypeName()
                                            val ids = resolveMemberIds(next.value, next.pos, implicitType)
                                            ThisMethodSlotCallRef(next.value, ids.methodId, args, true, isOptional)
                                        } else if (left.name == "scope") {
                                            if (next.value == "get" || next.value == "set") {
                                                val first = args.firstOrNull()?.value
                                                val const = (first as? ExpressionStatement)?.ref as? ConstRef
                                                val name = const?.constValue as? ObjString
                                                if (name != null) {
                                                    resolutionSink?.referenceReflection(name.value, next.pos)
                                                }
                                            }
                                            MethodCallRef(left, next.value, args, true, isOptional)
                                        } else {
                                            val unionCall = buildUnionMethodCall(left, next.value, next.pos, isOptional, args, true)
                                            if (unionCall != null) {
                                                unionCall
                                            } else {
                                                enforceReceiverTypeForMember(left, next.value, next.pos)
                                                MethodCallRef(left, next.value, args, true, isOptional)
                                            }
                                        }
                                        is QualifiedThisRef ->
                                            QualifiedThisMethodSlotCallRef(
                                                left.typeName,
                                                next.value,
                                                resolveMemberIds(next.value, next.pos, left.typeName).methodId,
                                                args,
                                                true,
                                                isOptional
                                            ).also {
                                                resolutionSink?.referenceMember(next.value, next.pos, left.typeName)
                                            }
                                        else -> {
                                            val unionCall = buildUnionMethodCall(left, next.value, next.pos, isOptional, args, true)
                                            if (unionCall != null) {
                                                unionCall
                                            } else {
                                                enforceReceiverTypeForMember(left, next.value, next.pos)
                                                MethodCallRef(left, next.value, args, true, isOptional)
                                            }
                                        }
                                    }
                                }

                                else -> {}
                            }
                        }
                        if (!isCall) {
                            operand = when (left) {
                                is LocalVarRef -> if (left.name == "this") {
                                    resolutionSink?.referenceMember(next.value, next.pos)
                                    val implicitType = currentImplicitThisTypeName()
                                    val ids = resolveMemberIds(next.value, next.pos, implicitType)
                                    ThisFieldSlotRef(next.value, ids.fieldId, ids.methodId, isOptional)
                                } else {
                                    val unionField = buildUnionFieldAccess(left, next.value, next.pos, isOptional)
                                    if (unionField != null) {
                                        unionField
                                    } else {
                                        enforceReceiverTypeForMember(left, next.value, next.pos)
                                        FieldRef(left, next.value, isOptional)
                                    }
                                }
                                is QualifiedThisRef -> run {
                                    val ids = resolveMemberIds(next.value, next.pos, left.typeName)
                                    QualifiedThisFieldSlotRef(
                                        left.typeName,
                                        next.value,
                                        ids.fieldId,
                                        ids.methodId,
                                        isOptional
                                    )
                                }.also {
                                    resolutionSink?.referenceMember(next.value, next.pos, left.typeName)
                                }
                                else -> {
                                    val unionField = buildUnionFieldAccess(left, next.value, next.pos, isOptional)
                                    if (unionField != null) {
                                        unionField
                                    } else {
                                        enforceReceiverTypeForMember(left, next.value, next.pos)
                                        FieldRef(left, next.value, isOptional)
                                    }
                                }
                            }
                        }
                    }

                        ?: throw ScriptError(t.pos, "Expecting expression before dot")
                }

                Token.Type.COLONCOLON -> {
                    operand = parseScopeOperator(operand)
                }

                Token.Type.LT -> {
                    val parsedTypeArgs = operand
                        ?.takeIf { isGenericCallCalleeCandidate(it) }
                        ?.let { tryParseCallTypeArgsAfterLt() }
                    if (parsedTypeArgs != null) {
                        pendingCallTypeArgs = parsedTypeArgs
                        continue
                    }
                    cc.previous()
                    return operand
                }

                Token.Type.LPAREN, Token.Type.NULL_COALESCE_INVOKE -> {
                    operand?.let { left ->
                        // this is function call from <left>
                        operand = parseFunctionCall(
                            left,
                            false,
                            t.type == Token.Type.NULL_COALESCE_INVOKE,
                            pendingCallTypeArgs
                        )
                        pendingCallTypeArgs = null
                    } ?: run {
                        // Expression in parentheses
                        val statement = parseStatement() ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = StatementRef(statement)
                        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                        cc.skipTokenOfType(Token.Type.RPAREN, "missing ')'")
                        pendingCallTypeArgs = null
                    }
                }

                Token.Type.LBRACKET, Token.Type.NULL_COALESCE_INDEX -> {
                    operand?.let { left ->
                        // array access via ObjRef
                        val isOptional = t.type == Token.Type.NULL_COALESCE_INDEX
                        val index = parseIndexExpression() ?: throw ScriptError(t.pos, "Expecting index expression")
                        cc.skipTokenOfType(Token.Type.RBRACKET, "missing ']' at the end of the list literal")
                        operand = IndexRef(left, index, isOptional)
                    } ?: run {
                        // array literal
                        val entries = parseArrayLiteral()
                        // build list literal via ObjRef node (no per-access lambdas)
                        operand = ListLiteralRef(entries)
                    }
                }

                Token.Type.ID -> {
                    // there could be terminal operators or keywords:// variable to read or like
                    when (t.value) {
                        in stopKeywords -> {
                            if (t.value == "init" && !(codeContexts.lastOrNull() is CodeContext.ClassBody && cc.peekNextNonWhitespace().type == Token.Type.LBRACE)) {
                                // Soft keyword: init is only a keyword in class body when followed by {
                                cc.previous()
                                operand = parseAccessor()
                            } else {
                                if (operand != null) throw ScriptError(t.pos, "unexpected keyword")
                                // Allow certain statement-like constructs to act as expressions
                                // when they appear in expression position (e.g., `if (...) ... else ...`).
                                // Other keywords should be handled by the outer statement parser.
                                when (t.value) {
                                    "if" -> {
                                        val s = parseIfStatement()
                                        operand = StatementRef(s)
                                    }

                                    "when" -> {
                                        val s = parseWhenStatement()
                                        operand = StatementRef(s)
                                    }

                                    else -> {
                                        // Do not consume the keyword as part of a term; backtrack
                                        // and return null so outer parser handles it.
                                        cc.previous()
                                        return null
                                    }
                                }
                            }
                        }

                        "else", "break", "continue" -> {
                            cc.previous()
                            return operand

                        }

                        "throw" -> {
                            val s = parseThrowStatement(t.pos)
                            operand = StatementRef(s)
                        }

                        else -> operand?.let { left ->
                            // selector: <lvalue>, '.' , <id>
                            // we replace operand with selector code, that
                            // is RW:
                            operand = when (left) {
                                is LocalVarRef -> if (left.name == "this") {
                                    resolutionSink?.referenceMember(t.value, t.pos)
                                    val ids = resolveMemberIds(t.value, t.pos, null)
                                    ThisFieldSlotRef(t.value, ids.fieldId, ids.methodId, false)
                                } else {
                                    FieldRef(left, t.value, false)
                                }
                                is QualifiedThisRef -> run {
                                    val ids = resolveMemberIds(t.value, t.pos, left.typeName)
                                    QualifiedThisFieldSlotRef(
                                        left.typeName,
                                        t.value,
                                        ids.fieldId,
                                        ids.methodId,
                                        false
                                    )
                                }.also {
                                    resolutionSink?.referenceMember(t.value, t.pos, left.typeName)
                                }
                                else -> FieldRef(left, t.value, false)
                            }
                        } ?: run {
                            // variable to read or like
                            cc.previous()
                            operand = parseAccessor()
                        }
                    }
                }

                Token.Type.PLUS2 -> {
                    // ++ (post if operand exists, pre otherwise)
                    operand = operand?.let { left ->
                        IncDecRef(left, isIncrement = true, isPost = true, atPos = startPos)
                    } ?: run {
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        IncDecRef(next, isIncrement = true, isPost = false, atPos = startPos)
                    }
                }

                Token.Type.MINUS2 -> {
                    // -- (post if operand exists, pre otherwise)
                    operand = operand?.let { left ->
                        IncDecRef(left, isIncrement = false, isPost = true, atPos = startPos)
                    } ?: run {
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        IncDecRef(next, isIncrement = false, isPost = false, atPos = startPos)
                    }
                }

                Token.Type.DOTDOT, Token.Type.DOTDOTLT -> {
                    // range operator
                    val isEndInclusive = t.type == Token.Type.DOTDOT
                    val left = operand
                    // if it is an open end range, then the end of line could be here that we do not want
                    // to skip in parseExpression:
                    val current = cc.current()
                    val right =
                        if (current.type == Token.Type.NEWLINE ||
                            current.type == Token.Type.SINGLE_LINE_COMMENT ||
                            current.type == Token.Type.STEP
                        )
                            null
                        else
                            parseExpression()
                    val rightRef = right?.let { StatementRef(it) }
                    if (left != null && rightRef != null) {
                        val lConst = constIntValueOrNull(left)
                        val rConst = constIntValueOrNull(rightRef)
                        if (lConst != null && rConst != null) {
                            operand = ConstRef(ObjRange(ObjInt.of(lConst), ObjInt.of(rConst), isEndInclusive).asReadonly)
                        } else {
                            operand = RangeRef(left, rightRef, isEndInclusive)
                        }
                    } else {
                        operand = RangeRef(left, rightRef, isEndInclusive)
                    }
                }

                Token.Type.STEP -> {
                    val left = operand ?: throw ScriptError(t.pos, "step requires a range")
                    val rangeRef = when (left) {
                        is RangeRef -> left
                        is ConstRef -> {
                            val range = left.constValue as? ObjRange ?: run {
                                cc.previous()
                                return operand
                            }
                            val leftRef = range.start?.takeUnless { it.isNull }?.let { ConstRef(it.asReadonly) }
                            val rightRef = range.end?.takeUnless { it.isNull }?.let { ConstRef(it.asReadonly) }
                            RangeRef(leftRef, rightRef, range.isEndInclusive)
                        }
                        else -> {
                            cc.previous()
                            return operand
                        }
                    }
                    if (rangeRef.step != null) throw ScriptError(t.pos, "step is already specified for this range")
                    val stepExpr = parseExpression() ?: throw ScriptError(t.pos, "Expected step expression")
                    val stepRef = StatementRef(stepExpr)
                    operand = RangeRef(rangeRef.left, rangeRef.right, rangeRef.isEndInclusive, stepRef)
                }

                Token.Type.LBRACE, Token.Type.NULL_COALESCE_BLOCKINVOKE -> {
                    operand = operand?.let { left ->
                        // Trailing block-argument function call: the leading '{' is already consumed,
                        // and the lambda must be parsed as a single argument BEFORE any following
                        // selectors like ".foo" are considered. Do NOT rewind here, otherwise
                        // the expression parser may capture ".foo" as part of the lambda expression.
                        parseFunctionCall(
                            left,
                            blockArgument = true,
                            isOptional = t.type == Token.Type.NULL_COALESCE_BLOCKINVOKE,
                            explicitTypeArgs = pendingCallTypeArgs
                        )
                    } ?: run {
                        // Disambiguate between lambda and map literal.
                        // Heuristic: if there is a top-level '->' before the closing '}', it's a lambda.
                        // Otherwise, try to parse a map literal; if it fails, fall back to lambda.
                        val isLambda = hasTopLevelArrowBeforeRbrace()
                        if (!isLambda) {
                            parseMapLiteralOrNull() ?: parseLambdaExpression()
                        } else parseLambdaExpression()
                    }
                }

                Token.Type.RBRACKET, Token.Type.RPAREN -> {
                    cc.previous()
                    return operand
                }

                else -> {
                    cc.previous()
                    operand?.let { return it }
                    operand = parseAccessor() ?: return null //throw ScriptError(t.pos, "Expecting expression")
                }
            }
        }
    }

    private suspend fun tryParseCallTypeArgsAfterLt(): List<TypeDecl>? {
        val savedAfterLt = cc.savePos()
        return try {
            val args = mutableListOf<TypeDecl>()
            do {
                val (argSem, _) = parseTypeExpressionWithMini()
                args += argSem
                val sep = cc.next()
                when (sep.type) {
                    Token.Type.COMMA -> continue
                    Token.Type.GT -> break
                    Token.Type.SHR -> {
                        cc.pushPendingGT()
                        break
                    }
                    else -> {
                        cc.restorePos(savedAfterLt)
                        return null
                    }
                }
            } while (true)
            val nextType = cc.peekNextNonWhitespace().type
            if (nextType != Token.Type.LPAREN && nextType != Token.Type.NULL_COALESCE_INVOKE) {
                cc.restorePos(savedAfterLt)
                return null
            }
            args
        } catch (_: ScriptError) {
            cc.restorePos(savedAfterLt)
            null
        }
    }

    private fun isGenericCallCalleeCandidate(ref: ObjRef): Boolean {
        val name = when (ref) {
            is LocalVarRef -> ref.name
            is FastLocalVarRef -> ref.name
            is LocalSlotRef -> ref.name
            else -> null
        }
        if (name != null) {
            if (lookupGenericFunctionDecl(name) != null) return true
            if (name.firstOrNull()?.isUpperCase() == true) return true
            return false
        }
        return ref is ConstRef && ref.constValue is ObjClass
    }

    /**
     * Parse lambda expression, leading '{' is already consumed
     */
    private suspend fun parseLambdaExpression(
        expectedReceiverType: String? = null,
        wrapAsExtensionCallable: Boolean = false,
        implicitItType: String? = null
    ): ObjRef {
        // lambda args are different:
        val startPos = cc.currentPos()
        val label = lastLabel
        val argsDeclaration = parseArgsDeclaration()
        if (argsDeclaration != null && argsDeclaration.endTokenType != Token.Type.ARROW)
            throw ScriptError(
                startPos,
                "lambda must have either valid arguments declaration with '->' or no arguments"
            )

        val paramNames = argsDeclaration?.params?.map { it.name } ?: emptyList()
        val hasImplicitIt = argsDeclaration == null
        val slotParamNames = if (hasImplicitIt) paramNames + "it" else paramNames
        val paramSlotPlan = buildParamSlotPlan(slotParamNames)
        if (implicitItType != null) {
            val cls = resolveClassByName(implicitItType)
                ?: resolveTypeDeclObjClass(TypeDecl.Simple(implicitItType, false))
            val itSlot = paramSlotPlan.slots["it"]?.index
            if (cls != null && itSlot != null) {
                val paramTypeMap = slotTypeByScopeId.getOrPut(paramSlotPlan.id) { mutableMapOf() }
                paramTypeMap[itSlot] = cls
            }
        }

        label?.let { cc.labels.add(it) }
        slotPlanStack.add(paramSlotPlan)
        val capturePlan = CapturePlan(paramSlotPlan, isFunction = true, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val parsedBody = try {
            inCodeContext(CodeContext.Function("<lambda>", implicitThisMembers = true, implicitThisTypeName = expectedReceiverType)) {
                val returnLabels = label?.let { setOf(it) } ?: emptySet()
                returnLabelStack.addLast(returnLabels)
                try {
                    resolutionSink?.enterScope(ScopeKind.FUNCTION, startPos, "<lambda>")
                    for (param in slotParamNames) {
                        resolutionSink?.declareSymbol(param, SymbolKind.PARAM, isMutable = false, pos = startPos)
                    }
                    lambdaDepth += 1
                    try {
                        withLocalNames(slotParamNames.toSet()) {
                            parseBlock(skipLeadingBrace = true)
                        }
                    } finally {
                        lambdaDepth -= 1
                    }
                } finally {
                    resolutionSink?.exitScope(cc.currentPos())
                    returnLabelStack.removeLast()
                }
            }
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        val body = unwrapBytecodeDeep(parsedBody)
        label?.let { cc.labels.remove(it) }

        val paramSlotPlanSnapshot = slotPlanIndices(paramSlotPlan)
        val captureSlots = capturePlan.captures.toList()
        val captureEntries = if (captureSlots.isNotEmpty()) {
            val modulePlan = moduleSlotPlan()
            captureSlots.map { capture ->
                val owner = capturePlan.captureOwners[capture.name]
                    ?: error("Missing capture owner for ${capture.name}")
                val isModuleSlot = modulePlan != null && owner.scopeId == modulePlan.id
                val ownerKind = if (isModuleSlot) {
                    net.sergeych.lyng.bytecode.CaptureOwnerFrameKind.MODULE
                } else {
                    net.sergeych.lyng.bytecode.CaptureOwnerFrameKind.LOCAL
                }
                net.sergeych.lyng.bytecode.LambdaCaptureEntry(
                    ownerKind = ownerKind,
                    ownerScopeId = owner.scopeId,
                    ownerSlotId = owner.slot,
                    ownerName = capture.name,
                    ownerIsMutable = owner.isMutable,
                    ownerIsDelegated = owner.isDelegated
                )
            }
        } else {
            emptyList()
        }
        val returnClass = inferReturnClassFromStatement(body)
        val paramKnownClasses = mutableMapOf<String, ObjClass>()
        argsDeclaration?.params?.forEach { param ->
            val cls = resolveTypeDeclObjClass(param.type) ?: return@forEach
            paramKnownClasses[param.name] = cls
        }
        val returnLabels = label?.let { setOf(it) } ?: emptySet()
        val extraKnownNames = if (compileClassInfos.isEmpty()) {
            paramKnownClasses
        } else {
            val merged = LinkedHashMap<String, ObjClass>()
            for (className in compileClassInfos.keys) {
                merged[className] = if (objectDeclNames.contains(className)) {
                    ObjDynamic.type
                } else {
                    ObjClassType
                }
            }
            merged.putAll(paramKnownClasses)
            merged
        }
        val fnStatements = if (compileBytecode) {
            returnLabelStack.addLast(returnLabels)
            try {
                wrapFunctionBytecode(
                    body,
                    "<lambda>",
                    extraKnownNames,
                    forcedLocalSlots = paramSlotPlanSnapshot,
                    forcedLocalScopeId = paramSlotPlan.id
                )
            } finally {
                returnLabelStack.removeLast()
            }
        } else {
            body
        }
        val bytecodeFn = (fnStatements as? BytecodeStatement)?.bytecodeFunction()
        val ref = LambdaFnRef(
            valueFn = { closureScope ->
            val captureRecords = closureScope.captureRecords
            val stmt = object : Statement(), BytecodeBodyProvider {
                override val pos: Pos = fnStatements.pos

                override fun bytecodeBody(): BytecodeStatement? = fnStatements as? BytecodeStatement

                override suspend fun execute(scope: Scope): Obj {
                    val context = scope.applyClosureForBytecode(closureScope, preferredThisType = expectedReceiverType).also {
                        it.args = scope.args
                    }
                    if (captureSlots.isNotEmpty()) {
                        if (captureRecords != null) {
                            context.captureRecords = captureRecords
                            context.captureNames = captureSlots.map { it.name }
                        } else {
                            val resolvedRecords = ArrayList<ObjRecord>(captureSlots.size)
                            val resolvedNames = ArrayList<String>(captureSlots.size)
                            for (capture in captureSlots) {
                                val rec = closureScope.chainLookupIgnoreClosure(
                                    capture.name,
                                    followClosure = true,
                                    caller = context.currentClassCtx
                                ) ?: closureScope.raiseSymbolNotFound("symbol ${capture.name} not found")
                                resolvedRecords.add(rec)
                                resolvedNames.add(capture.name)
                            }
                            context.captureRecords = resolvedRecords
                            context.captureNames = resolvedNames
                        }
                    }
                    val bytecodeBody = fnStatements as? BytecodeStatement
                        ?: scope.raiseIllegalState("non-bytecode lambda body encountered")
                    val bytecodeFn = bytecodeBody.bytecodeFunction()
                    val binder: suspend (net.sergeych.lyng.bytecode.CmdFrame, Arguments) -> Unit = { frame, arguments ->
                        val slotPlan = bytecodeFn.localSlotPlanByName()
                        if (argsDeclaration == null) {
                            val l = arguments.list
                            val itValue: Obj = when (l.size) {
                                0 -> ObjVoid
                                1 -> l[0]
                                else -> ObjList(l.toMutableList())
                            }
                            val itSlot = slotPlan["it"]
                            if (itSlot != null) {
                                frame.frame.setObj(itSlot, itValue)
                            }
                        } else {
                            argsDeclaration.assignToFrame(
                                context,
                                arguments,
                                slotPlan,
                                frame.frame
                            )
                        }
                    }
                    return try {
                        net.sergeych.lyng.bytecode.CmdVm().execute(bytecodeFn, context, scope.args, binder)
                    } catch (e: ReturnException) {
                        if (e.label == null || returnLabels.contains(e.label)) e.result
                        else throw e
                    }
                }
            }
            val callable: Obj = if (wrapAsExtensionCallable) {
                ObjExtensionMethodCallable("<lambda>", stmt)
            } else {
                stmt
            }
            callable.asReadonly
            },
            bytecodeFn = bytecodeFn,
            paramSlotPlan = paramSlotPlanSnapshot,
            argsDeclaration = argsDeclaration,
            captureEntries = captureEntries,
            preferredThisType = expectedReceiverType,
            wrapAsExtensionCallable = wrapAsExtensionCallable,
            returnLabels = returnLabels,
            pos = startPos
        )
        if (returnClass != null) {
            lambdaReturnTypeByRef[ref] = returnClass
        }
        if (captureEntries.isNotEmpty()) {
            lambdaCaptureEntriesByRef[ref] = captureEntries
        }
        return ref
    }

    private suspend fun parseArrayLiteral(): List<ListEntry> {
        // it should be called after Token.Type.LBRACKET is consumed
        val entries = mutableListOf<ListEntry>()
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.COMMA -> {
                    // todo: check commas sequences like [,] [,,] before, after or instead of expressions
                }

                Token.Type.RBRACKET -> return entries
                Token.Type.ELLIPSIS -> {
                    parseExpressionLevel()?.let { entries += ListEntry.Spread(it) }
                        ?: throw ScriptError(t.pos, "spread element must have an expression")
                }

                else -> {
                    cc.previous()
                    parseExpressionLevel()?.let { expr ->
                        if (cc.current().type == Token.Type.ELLIPSIS) {
                            cc.next()
                            entries += ListEntry.Spread(expr)
                        } else {
                            entries += ListEntry.Element(expr)
                        }
                    } ?: throw ScriptError(t.pos, "invalid list literal: expecting expression")
                }
            }
        }
    }

    private suspend fun parseIndexExpression(): ObjRef? {
        val first = parseExpressionLevel() ?: return null
        if (!cc.skipTokenOfType(Token.Type.COMMA, isOptional = true)) {
            return first
        }

        val entries = mutableListOf<ListEntry>(ListEntry.Element(first))
        do {
            val next = parseExpressionLevel() ?: throw ScriptError(cc.currentPos(), "Expecting index expression")
            entries += ListEntry.Element(next)
        } while (cc.skipTokenOfType(Token.Type.COMMA, isOptional = true))
        return ListLiteralRef(entries)
    }

    private suspend fun parseDestructuringPattern(): List<ListEntry> {
        // it should be called after Token.Type.LBRACKET is consumed
        val entries = mutableListOf<ListEntry>()
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.COMMA -> {
                    // allow trailing/extra commas
                }
                Token.Type.RBRACKET -> return entries
                Token.Type.LBRACKET -> {
                    val nested = parseDestructuringPattern()
                    entries += ListEntry.Element(ListLiteralRef(nested))
                }
                Token.Type.ELLIPSIS -> {
                    val id = cc.requireToken(Token.Type.ID, "Expected identifier after ...")
                    val ref = LocalVarRef(id.value, id.pos)
                    entries += ListEntry.Spread(ref)
                }
                Token.Type.ID -> {
                    val ref = LocalVarRef(t.value, t.pos)
                    if (cc.peekNextNonWhitespace().type == Token.Type.ELLIPSIS) {
                        cc.next()
                        entries += ListEntry.Spread(ref)
                    } else {
                        entries += ListEntry.Element(ref)
                    }
                }
                else -> throw ScriptError(t.pos, "invalid destructuring pattern: expected identifier")
            }
        }
    }

    private fun parseScopeOperator(operand: ObjRef?): ObjRef {
        // implement global scope maybe?
        if (operand == null) throw ScriptError(cc.next().pos, "Expecting expression before ::")
        val t = cc.next()
        if (t.type != Token.Type.ID) throw ScriptError(t.pos, "Expecting ID after ::")
        return when (t.value) {
            "class" -> {
                val ref = ClassOperatorRef(operand, t.pos)
                lambdaReturnTypeByRef[ref] = ObjClassType
                ref
            }

            else -> throw ScriptError(t.pos, "Unknown scope operation: ${t.value}")
        }
    }

    /**
     * Look ahead from current position (right after a leading '{') to find a top-level '->' before the matching '}'.
     * Returns true if such arrow is found, meaning the construct should be parsed as a lambda.
     * The scanner respects nested braces depth; only depth==1 arrows count.
     * The current cursor is restored on exit.
     */
    private fun hasTopLevelArrowBeforeRbrace(): Boolean {
        val start = cc.savePos()
        var depth = 1
        var found = false
        while (cc.hasNext()) {
            val t = cc.next()
            when (t.type) {
                Token.Type.LBRACE -> depth++
                Token.Type.RBRACE -> {
                    depth--
                    if (depth == 0) break
                }
                Token.Type.ARROW -> if (depth == 1) {
                    found = true
                    // Do not break; we still restore position below
                }
                else -> {}
            }
        }
        cc.restorePos(start)
        return found
    }

    /**
     * Attempt to parse a map literal starting at the position after '{'.
     * Returns null if the sequence does not look like a map literal (e.g., empty or first token is not STRING/ID/ELLIPSIS),
     * in which case caller should treat it as a lambda/block.
     * When it recognizes a map literal, it commits and throws on syntax errors.
     */
    private suspend fun parseMapLiteralOrNull(): ObjRef? {
        val startAfterLbrace = cc.savePos()
        // Peek first non-ws token to decide whether it's likely a map literal
        val first = cc.peekNextNonWhitespace()
        // Empty {} should be parsed as an empty map literal in expression context
        if (first.type == Token.Type.RBRACE) {
            // consume '}' and return empty map literal
            cc.next() // consume the RBRACE
            return MapLiteralRef(emptyList())
        }
        if (first.type !in listOf(Token.Type.STRING, Token.Type.ID, Token.Type.ELLIPSIS)) return null

        // Commit to map literal parsing
        cc.skipWsTokens()
        val entries = mutableListOf<MapLiteralEntry>()
        val usedKeys = mutableSetOf<String>()

        while (true) {
            // Skip whitespace/comments/newlines between entries
            val t0 = cc.nextNonWhitespace()
            when (t0.type) {
                Token.Type.RBRACE -> {
                    // end of map literal
                    return MapLiteralRef(entries)
                }
                Token.Type.COMMA -> {
                    // allow stray commas; continue
                    continue
                }
                Token.Type.ELLIPSIS -> {
                    // spread element: ... expression
                    val expr = parseExpressionLevel() ?: throw ScriptError(t0.pos, "invalid map spread: expecting expression")
                    entries += MapLiteralEntry.Spread(expr)
                    // Expect comma or '}' next; loop will handle
                }
                Token.Type.STRING, Token.Type.ID -> {
                    val isIdKey = t0.type == Token.Type.ID
                    val keyName = if (isIdKey) t0.value else t0.value
                    // After key we require ':'
                    cc.skipWsTokens()
                    val colon = cc.next()
                    if (colon.type != Token.Type.COLON) {
                        // Not a map literal after all; backtrack and signal null
                        cc.restorePos(startAfterLbrace)
                        return null
                    }
                    // Check for shorthand (only for id-keys): if next non-ws is ',' or '}'
                    cc.skipWsTokens()
                    val next = cc.next()
                    if ((next.type == Token.Type.COMMA || next.type == Token.Type.RBRACE)) {
                        if (!isIdKey) throw ScriptError(next.pos, "missing value after string-literal key '$keyName'")
                        // id: shorthand; value is the variable with the same name
                        // rewind one step if RBRACE so outer loop can handle it
                        if (next.type == Token.Type.RBRACE) cc.previous()
                        // Duplicate detection for literals only
                        if (!usedKeys.add(keyName)) throw ScriptError(t0.pos, "duplicate key '$keyName'")
                        resolutionSink?.reference(keyName, t0.pos)
                        entries += MapLiteralEntry.Named(keyName, resolveIdentifierRef(keyName, t0.pos))
                        // If the token was COMMA, the loop continues; if it's RBRACE, next iteration will end
                    } else {
                        // There is a value expression: push back token and parse expression
                        cc.previous()
                        val valueRef = parseExpressionLevel() ?: throw ScriptError(colon.pos, "expecting map entry value")
                        if (!usedKeys.add(keyName)) throw ScriptError(t0.pos, "duplicate key '$keyName'")
                        entries += MapLiteralEntry.Named(keyName, valueRef)
                        // After value, allow optional comma; do not require it
                        cc.skipTokenOfType(Token.Type.COMMA, isOptional = true)
                        // The loop will continue and eventually see '}'
                    }
                }
                else -> {
                    // Not a map literal; backtrack and let caller treat as lambda
                    cc.restorePos(startAfterLbrace)
                    return null
                }
            }
        }
    }

    /**
     * Parse argument declaration, used in lambda (and later in fn too)
     * @return declaration or null if there is no valid list of arguments
     */
    private suspend fun parseArgsDeclaration(isClassDeclaration: Boolean = false): ArgsDeclaration? {
        val result = mutableListOf<ArgsDeclaration.Item>()
        var endTokenType: Token.Type? = null
        val startPos = cc.savePos()

        cc.skipWsTokens()

        while (endTokenType == null) {
            var t = cc.next()
            when (t.type) {
                Token.Type.RPAREN, Token.Type.ARROW -> {
                    // empty list?
                    endTokenType = t.type
                }

                Token.Type.NEWLINE -> {}
                Token.Type.MULTILINE_COMMENT, Token.Type.SINGLE_LINE_COMMENT -> {}

                Token.Type.ID, Token.Type.ATLABEL -> {
                    var isTransient = false
                    if (t.type == Token.Type.ATLABEL) {
                        if (t.value == "Transient") {
                            isTransient = true
                            t = cc.next()
                        } else throw ScriptError(t.pos, "Unexpected label in argument list")
                    }

                    // visibility
                    val visibility = if (isClassDeclaration && t.value == "private") {
                        t = cc.next()
                        Visibility.Private
                    } else Visibility.Public

                    // val/var?
                    val access = when (t.value) {
                        "val" -> {
                            if (!isClassDeclaration) {
                                cc.restorePos(startPos); return null
                            }
                            t = cc.next()
                            AccessType.Val
                        }

                        "var" -> {
                            if (!isClassDeclaration) {
                                cc.restorePos(startPos); return null
                            }
                            t = cc.next()
                            AccessType.Var
                        }

                        else -> null
                    }
                    val effectiveAccess = if (isClassDeclaration && access == null) {
                        AccessType.Var
                    } else {
                        access
                    }

                    // Nullable shorthand: "x?" means Object? (or makes explicit type nullable)
                    var nullableHint = false
                    val nullableHintPos = cc.savePos()
                    cc.skipWsTokens()
                    if (cc.peekNextNonWhitespace().type == Token.Type.QUESTION) {
                        cc.nextNonWhitespace()
                        nullableHint = true
                    } else {
                        cc.restorePos(nullableHintPos)
                    }

                    // type information (semantic + mini syntax)
                    if (nullableHint) {
                        val afterHint = cc.savePos()
                        cc.skipWsTokens()
                        if (cc.peekNextNonWhitespace().type == Token.Type.COLON) {
                            throw ScriptError(t.pos, "nullable shorthand '?' cannot be combined with explicit type")
                        }
                        cc.restorePos(afterHint)
                    }
                    var (typeInfo, miniType) = parseTypeDeclarationWithMini()
                    if (nullableHint) {
                        typeInfo = makeTypeDeclNullable(typeInfo)
                        miniType = miniType?.let { makeMiniTypeNullable(it) }
                    }

                    var defaultValue: Obj? = null
                    cc.ifNextIs(Token.Type.ASSIGN) {
                        val expr = parseExpression()
                            ?: throw ScriptError(cc.current().pos, "Expected default value expression")
                        defaultValue = wrapBytecode(expr)
                    }
                    val isEllipsis = cc.skipTokenOfType(Token.Type.ELLIPSIS, isOptional = true)
                    result += ArgsDeclaration.Item(
                        t.value,
                        typeInfo,
                        miniType,
                        t.pos,
                        isEllipsis,
                        defaultValue,
                        effectiveAccess,
                        visibility,
                        isTransient
                    )

                    // important: valid argument list continues with ',' and ends with '->' or ')'
                    // otherwise it is not an argument list:
                    when (val tt = cc.nextNonWhitespace().type) {
                        Token.Type.RPAREN -> {
                            // end of arguments
                            endTokenType = tt
                        }

                        Token.Type.ARROW -> {
                            // end of arguments too
                            endTokenType = tt
                        }

                        Token.Type.COMMA -> {
                            // next argument, OK
                        }

                        else -> {
                            // this is not a valid list of arguments:
                            cc.restorePos(startPos) // for the current
                            return null
                        }
                    }
                }

                else -> {
                    // if we get here. there os also no valid list of arguments:
                    cc.restorePos(startPos)
                    return null
                }
            }
        }
        return ArgsDeclaration(result, endTokenType)
    }

    @Suppress("unused")
    private fun parseTypeDeclaration(): TypeDecl {
        return parseTypeDeclarationWithMini().first
    }

    // Minimal helper to parse a type annotation and simultaneously build a MiniTypeRef.
    // Currently supports a simple identifier with optional nullable '?'.
    private fun parseTypeDeclarationWithMini(): Pair<TypeDecl, MiniTypeRef?> {
        // Only parse a type if a ':' follows; otherwise keep current behavior
        if (!cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) return Pair(TypeDecl.TypeAny, null)
        return parseTypeExpressionWithMini()
    }

    private fun parseTypeExpressionWithMini(): Pair<TypeDecl, MiniTypeRef> {
        val start = cc.currentPos()
        val (decl, mini) = parseTypeUnionWithMini()
        return expandTypeAliases(decl, start) to mini
    }

    private fun expandTypeAliases(type: TypeDecl, pos: Pos, seen: MutableSet<String> = mutableSetOf()): TypeDecl {
        return when (type) {
            is TypeDecl.Simple -> expandTypeAliasReference(type.name, emptyList(), type.isNullable, pos, seen) ?: type
            is TypeDecl.Generic -> {
                val expandedArgs = type.args.map { expandTypeAliases(it, pos, seen) }
                expandTypeAliasReference(type.name, expandedArgs, type.isNullable, pos, seen)
                    ?: TypeDecl.Generic(type.name, expandedArgs, type.isNullable)
            }
            is TypeDecl.Function -> {
                val receiver = type.receiver?.let { expandTypeAliases(it, pos, seen) }
                val params = type.params.map { expandTypeAliases(it, pos, seen) }
                val ret = expandTypeAliases(type.returnType, pos, seen)
                TypeDecl.Function(receiver, params, ret, type.nullable)
            }
            is TypeDecl.Ellipsis -> {
                val elem = expandTypeAliases(type.elementType, pos, seen)
                TypeDecl.Ellipsis(elem, type.nullable)
            }
            is TypeDecl.Union -> {
                val options = type.options.map { expandTypeAliases(it, pos, seen) }
                TypeDecl.Union(options, type.isNullable)
            }
            is TypeDecl.Intersection -> {
                val options = type.options.map { expandTypeAliases(it, pos, seen) }
                TypeDecl.Intersection(options, type.isNullable)
            }
            else -> type
        }
    }

    private fun expandTypeAliasReference(
        name: String,
        args: List<TypeDecl>,
        isNullable: Boolean,
        pos: Pos,
        seen: MutableSet<String>
    ): TypeDecl? {
        val alias = run {
            if (!name.contains('.')) {
                val classCtx = currentEnclosingClassName()
                if (classCtx != null) {
                    typeAliases["$classCtx.$name"]?.let { return@run it }
                }
            }
            typeAliases[name] ?: typeAliases[name.substringAfterLast('.')]
        } ?: return null
        if (!seen.add(alias.name)) throw ScriptError(pos, "circular type alias: ${alias.name}")
        val bindings = buildTypeAliasBindings(alias, args, pos)
        val substituted = substituteTypeAliasTypeVars(alias.body, bindings)
        val expanded = expandTypeAliases(substituted, pos, seen)
        seen.remove(alias.name)
        return if (isNullable) makeTypeDeclNullable(expanded) else expanded
    }

    private fun buildTypeAliasBindings(
        alias: TypeAliasDecl,
        args: List<TypeDecl>,
        pos: Pos
    ): Map<String, TypeDecl> {
        val params = alias.typeParams
        val resolvedArgs = if (args.size >= params.size) {
            if (args.size > params.size) {
                throw ScriptError(pos, "type alias ${alias.name} expects ${params.size} type arguments")
            }
            args.toMutableList()
        } else {
            val filled = args.toMutableList()
            for (param in params.drop(args.size)) {
                val fallback = param.defaultType
                    ?: throw ScriptError(pos, "type alias ${alias.name} expects ${params.size} type arguments")
                filled.add(fallback)
            }
            filled
        }
        val bindings = mutableMapOf<String, TypeDecl>()
        for ((index, param) in params.withIndex()) {
            val arg = resolvedArgs[index]
            val bound = param.bound
            if (bound != null && arg !is TypeDecl.TypeVar && !typeDeclSatisfiesBound(arg, bound)) {
                throw ScriptError(pos, "type alias ${alias.name} type argument ${param.name} does not satisfy bound")
            }
            bindings[param.name] = arg
        }
        return bindings
    }

    private fun substituteTypeAliasTypeVars(type: TypeDecl, bindings: Map<String, TypeDecl>): TypeDecl {
        return when (type) {
            is TypeDecl.TypeVar -> {
                val bound = bindings[type.name] ?: return type
                if (type.isNullable) makeTypeDeclNullable(bound) else bound
            }
            is TypeDecl.Simple -> type
            is TypeDecl.Generic -> {
                val args = type.args.map { substituteTypeAliasTypeVars(it, bindings) }
                TypeDecl.Generic(type.name, args, type.isNullable)
            }
            is TypeDecl.Function -> {
                val receiver = type.receiver?.let { substituteTypeAliasTypeVars(it, bindings) }
                val params = type.params.map { substituteTypeAliasTypeVars(it, bindings) }
                val ret = substituteTypeAliasTypeVars(type.returnType, bindings)
                TypeDecl.Function(receiver, params, ret, type.nullable)
            }
            is TypeDecl.Ellipsis -> {
                val elem = substituteTypeAliasTypeVars(type.elementType, bindings)
                TypeDecl.Ellipsis(elem, type.nullable)
            }
            is TypeDecl.Union -> {
                val options = type.options.map { substituteTypeAliasTypeVars(it, bindings) }
                TypeDecl.Union(options, type.isNullable)
            }
            is TypeDecl.Intersection -> {
                val options = type.options.map { substituteTypeAliasTypeVars(it, bindings) }
                TypeDecl.Intersection(options, type.isNullable)
            }
            else -> type
        }
    }

    private fun makeTypeDeclNullable(type: TypeDecl): TypeDecl {
        if (type.isNullable) return type
        return when (type) {
            TypeDecl.TypeAny -> TypeDecl.TypeNullableAny
            TypeDecl.TypeNullableAny -> type
            is TypeDecl.Function -> type.copy(nullable = true)
            is TypeDecl.Ellipsis -> type.copy(nullable = true)
            is TypeDecl.TypeVar -> type.copy(nullable = true)
            is TypeDecl.Union -> type.copy(nullable = true)
            is TypeDecl.Intersection -> type.copy(nullable = true)
            is TypeDecl.Simple -> TypeDecl.Simple(type.name, true)
            is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, true)
        }
    }

    private fun makeTypeDeclNonNullable(type: TypeDecl): TypeDecl {
        if (!type.isNullable) return type
        return when (type) {
            TypeDecl.TypeAny -> type
            TypeDecl.TypeNullableAny -> TypeDecl.TypeAny
            is TypeDecl.Function -> type.copy(nullable = false)
            is TypeDecl.Ellipsis -> type.copy(nullable = false)
            is TypeDecl.TypeVar -> type.copy(nullable = false)
            is TypeDecl.Union -> type.copy(nullable = false)
            is TypeDecl.Intersection -> type.copy(nullable = false)
            is TypeDecl.Simple -> TypeDecl.Simple(type.name, false)
            is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, false)
        }
    }

    private fun makeMiniTypeNullable(type: MiniTypeRef): MiniTypeRef {
        return when (type) {
            is MiniTypeName -> type.copy(nullable = true)
            is MiniGenericType -> type.copy(nullable = true)
            is MiniFunctionType -> type.copy(nullable = true)
            is MiniTypeVar -> type.copy(nullable = true)
            is MiniTypeUnion -> type.copy(nullable = true)
            is MiniTypeIntersection -> type.copy(nullable = true)
        }
    }

    private fun parseTypeUnionWithMini(): Pair<TypeDecl, MiniTypeRef> {
        var left = parseTypeIntersectionWithMini()
        val options = mutableListOf(left)
        while (cc.skipTokenOfType(Token.Type.BITOR, isOptional = true)) {
            options += parseTypeIntersectionWithMini()
        }
        if (options.size == 1) return left
        val rangeStart = options.first().second.range.start
        val rangeEnd = cc.currentPos()
        val mini = MiniTypeUnion(MiniRange(rangeStart, rangeEnd), options.map { it.second }, nullable = false)
        return TypeDecl.Union(options.map { it.first }, nullable = false) to mini
    }

    private fun parseTypeIntersectionWithMini(): Pair<TypeDecl, MiniTypeRef> {
        var left = parseTypePrimaryWithMini()
        val options = mutableListOf(left)
        while (cc.skipTokenOfType(Token.Type.BITAND, isOptional = true)) {
            options += parseTypePrimaryWithMini()
        }
        if (options.size == 1) return left
        val rangeStart = options.first().second.range.start
        val rangeEnd = cc.currentPos()
        val mini = MiniTypeIntersection(MiniRange(rangeStart, rangeEnd), options.map { it.second }, nullable = false)
        return TypeDecl.Intersection(options.map { it.first }, nullable = false) to mini
    }

    private fun parseTypePrimaryWithMini(): Pair<TypeDecl, MiniTypeRef> {
        parseFunctionTypeWithMini()?.let { return it }
        return parseSimpleTypeExpressionWithMini()
    }

    private fun parseFunctionTypeWithMini(): Pair<TypeDecl, MiniTypeRef>? {
        val saved = cc.savePos()
        val startPos = cc.currentPos()

        fun parseParamTypes(): List<Pair<TypeDecl, MiniTypeRef>> {
            val params = mutableListOf<Pair<TypeDecl, MiniTypeRef>>()
            var seenEllipsis = false
            cc.skipWsTokens()
            if (cc.peekNextNonWhitespace().type == Token.Type.RPAREN) {
                cc.nextNonWhitespace()
                return params
            }
            while (true) {
                cc.skipWsTokens()
                val next = cc.peekNextNonWhitespace()
                if (next.type == Token.Type.ELLIPSIS) {
                    val ell = cc.nextNonWhitespace()
                    if (seenEllipsis) {
                        ell.raiseSyntax("function type can contain only one ellipsis")
                    }
                    seenEllipsis = true
                    val paramDecl = TypeDecl.Ellipsis(TypeDecl.TypeAny)
                    val mini = MiniTypeName(
                        MiniRange(ell.pos, ell.pos),
                        listOf(MiniTypeName.Segment("Object", MiniRange(ell.pos, ell.pos))),
                        nullable = false
                    )
                    params += paramDecl to mini
                } else {
                    val (paramDecl, paramMini) = parseTypeExpressionWithMini()
                    val finalDecl = if (cc.skipTokenOfType(Token.Type.ELLIPSIS, isOptional = true)) {
                        if (seenEllipsis) {
                            cc.current().raiseSyntax("function type can contain only one ellipsis")
                        }
                        seenEllipsis = true
                        TypeDecl.Ellipsis(paramDecl)
                    } else {
                        paramDecl
                    }
                    params += finalDecl to paramMini
                }
                val sep = cc.nextNonWhitespace()
                when (sep.type) {
                    Token.Type.COMMA -> continue
                    Token.Type.RPAREN -> return params
                    else -> sep.raiseSyntax("expected ',' or ')' in function type")
                }
            }
        }

        var receiverDecl: TypeDecl? = null
        var receiverMini: MiniTypeRef? = null

        val first = cc.peekNextNonWhitespace()
        if (first.type == Token.Type.LPAREN) {
            cc.nextNonWhitespace()
        } else {
            val recv = parseSimpleTypeExpressionWithMini()
            val dotPos = cc.savePos()
            if (cc.skipTokenOfType(Token.Type.DOT, isOptional = true) && cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                receiverDecl = recv.first
                receiverMini = recv.second
                cc.nextNonWhitespace()
            } else {
                cc.restorePos(saved)
                return null
            }
        }

        val params = parseParamTypes()
        val arrow = cc.nextNonWhitespace()
        if (arrow.type != Token.Type.ARROW) {
            cc.restorePos(saved)
            return null
        }
        val (retDecl, retMini) = parseTypeExpressionWithMini()

        val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
            true
        } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
            cc.pushPendingAssign()
            true
        } else false

        val normalizedReceiverDecl = receiverDecl?.let { decl ->
            if (decl is TypeDecl.Simple && (decl.name == "Object" || decl.name == "Obj")) null else decl
        }
        val normalizedReceiverMini = if (normalizedReceiverDecl == null) null else receiverMini
        val rangeStart = when (normalizedReceiverMini) {
            null -> startPos
            else -> normalizedReceiverMini.range.start
        }
        val rangeEnd = cc.currentPos()
        val mini = MiniFunctionType(
            range = MiniRange(rangeStart, rangeEnd),
            receiver = normalizedReceiverMini,
            params = params.map { it.second },
            returnType = retMini,
            nullable = isNullable
        )
        val sem = TypeDecl.Function(
            receiver = normalizedReceiverDecl,
            params = params.map { it.first },
            returnType = retDecl,
            nullable = isNullable
        )
        return sem to mini
    }

    private fun parseSimpleTypeExpressionWithMini(): Pair<TypeDecl, MiniTypeRef> {
        // Parse a qualified base name: ID ('.' ID)*
        val segments = mutableListOf<MiniTypeName.Segment>()
        var first = true
        val typeStart = cc.currentPos()
        var lastEnd = typeStart
        var lastName = ""
        var lastPos = typeStart
        while (true) {
            val idTok =
                if (first) cc.requireToken(Token.Type.ID, "type name or type expression required") else cc.requireToken(
                    Token.Type.ID,
                    "identifier expected after '.' in type"
                )
            first = false
            segments += MiniTypeName.Segment(idTok.value, MiniRange(idTok.pos, idTok.pos))
            lastEnd = cc.currentPos()
            lastName = idTok.value
            lastPos = idTok.pos
            val dotPos = cc.savePos()
            val t = cc.next()
            if (t.type == Token.Type.DOT) {
                val nextAfterDot = cc.peekNextNonWhitespace()
                if (nextAfterDot.type == Token.Type.LPAREN) {
                    cc.restorePos(dotPos)
                    break
                }
                continue
            } else {
                cc.restorePos(dotPos)
                break
            }
        }

        val qualified = segments.joinToString(".") { it.name }
        val typeParams = currentTypeParams()
        if (segments.size == 1 && typeParams.contains(qualified)) {
            val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
                true
            } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
                cc.pushPendingAssign()
                true
            } else false
            val rangeEnd = cc.currentPos()
            val miniRef = MiniTypeVar(MiniRange(typeStart, rangeEnd), qualified, isNullable)
            return TypeDecl.TypeVar(qualified, isNullable) to miniRef
        }
        if (segments.size > 1) {
            resolutionSink?.reference(qualified, lastPos)
        } else {
            resolutionSink?.reference(lastName, lastPos)
        }
        // Helper to build MiniTypeRef (base or generic)
        fun buildBaseRef(rangeEnd: Pos, args: List<MiniTypeRef>?, nullable: Boolean): MiniTypeRef {
            val base = MiniTypeName(MiniRange(typeStart, rangeEnd), segments.toList(), nullable = false)
            return if (args == null || args.isEmpty()) base.copy(
                range = MiniRange(typeStart, rangeEnd),
                nullable = nullable
            )
            else MiniGenericType(MiniRange(typeStart, rangeEnd), base, args, nullable)
        }

        // Optional generic arguments: '<' Type (',' Type)* '>'
        var miniArgs: MutableList<MiniTypeRef>? = null
        var semArgs: MutableList<TypeDecl>? = null
        val afterBasePos = cc.savePos()
        if (cc.skipTokenOfType(Token.Type.LT, isOptional = true)) {
            miniArgs = mutableListOf()
            semArgs = mutableListOf()
            do {
                val (argSem, argMini) = parseTypeExpressionWithMini()
                miniArgs += argMini
                semArgs += argSem

                val sep = cc.next()
                if (sep.type == Token.Type.COMMA) {
                    // continue
                } else if (sep.type == Token.Type.GT) {
                    break
                } else if (sep.type == Token.Type.SHR) {
                    cc.pushPendingGT()
                    break
                } else {
                    sep.raiseSyntax("expected ',' or '>' in generic arguments")
                }
            } while (true)
            lastEnd = cc.currentPos()
        } else {
            cc.restorePos(afterBasePos)
        }

        // Nullable suffix after base or generic
        val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
            true
        } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
            cc.pushPendingAssign()
            true
        } else false
        val endPos = cc.currentPos()

        val miniRef = buildBaseRef(if (miniArgs != null) endPos else lastEnd, miniArgs, isNullable)
        // Semantic: keep simple for now, just use qualified base name with nullable flag
        val sem = if (semArgs != null) TypeDecl.Generic(qualified, semArgs, isNullable)
        else TypeDecl.Simple(qualified, isNullable)
        return Pair(sem, miniRef)
    }

    private fun parseExtensionReceiverTypeWithMini(): Pair<TypeDecl, MiniTypeRef> {
        val segments = mutableListOf<MiniTypeName.Segment>()
        var first = true
        val typeStart = cc.currentPos()
        var lastEnd = typeStart
        var lastName = ""
        var lastPos = typeStart
        while (true) {
            val idTok =
                if (first) cc.requireToken(Token.Type.ID, "type name or type expression required") else cc.requireToken(
                    Token.Type.ID,
                    "identifier expected after '.' in type"
                )
            first = false
            segments += MiniTypeName.Segment(idTok.value, MiniRange(idTok.pos, idTok.pos))
            lastEnd = cc.currentPos()
            lastName = idTok.value
            lastPos = idTok.pos
            val dotPos = cc.savePos()
            val t = cc.next()
            if (t.type == Token.Type.DOT) {
                val nextAfterDot = cc.peekNextNonWhitespace()
                if (nextAfterDot.type != Token.Type.ID) {
                    cc.restorePos(dotPos)
                    break
                }
                cc.nextNonWhitespace()
                val afterSegment = cc.peekNextNonWhitespace()
                if (afterSegment.type != Token.Type.DOT &&
                    afterSegment.type != Token.Type.LT &&
                    afterSegment.type != Token.Type.QUESTION &&
                    afterSegment.type != Token.Type.IFNULLASSIGN
                ) {
                    cc.restorePos(dotPos)
                    break
                }
                segments += MiniTypeName.Segment(nextAfterDot.value, MiniRange(nextAfterDot.pos, nextAfterDot.pos))
                lastEnd = cc.currentPos()
                lastName = nextAfterDot.value
                lastPos = nextAfterDot.pos
                continue
            } else {
                cc.restorePos(dotPos)
                break
            }
        }

        val qualified = segments.joinToString(".") { it.name }
        val typeParams = currentTypeParams()
        if (segments.size == 1 && typeParams.contains(qualified)) {
            val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
                true
            } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
                cc.pushPendingAssign()
                true
            } else false
            val rangeEnd = cc.currentPos()
            val miniRef = MiniTypeVar(MiniRange(typeStart, rangeEnd), qualified, isNullable)
            return TypeDecl.TypeVar(qualified, isNullable) to miniRef
        }
        if (segments.size > 1) {
            resolutionSink?.reference(qualified, lastPos)
        } else {
            resolutionSink?.reference(lastName, lastPos)
        }
        fun buildBaseRef(rangeEnd: Pos, args: List<MiniTypeRef>?, nullable: Boolean): MiniTypeRef {
            val base = MiniTypeName(MiniRange(typeStart, rangeEnd), segments.toList(), nullable = false)
            return if (args == null || args.isEmpty()) base.copy(
                range = MiniRange(typeStart, rangeEnd),
                nullable = nullable
            )
            else MiniGenericType(MiniRange(typeStart, rangeEnd), base, args, nullable)
        }

        var miniArgs: MutableList<MiniTypeRef>? = null
        var semArgs: MutableList<TypeDecl>? = null
        val afterBasePos = cc.savePos()
        if (cc.skipTokenOfType(Token.Type.LT, isOptional = true)) {
            miniArgs = mutableListOf()
            semArgs = mutableListOf()
            do {
                val (argSem, argMini) = parseTypeExpressionWithMini()
                miniArgs += argMini
                semArgs += argSem

                val sep = cc.next()
                if (sep.type == Token.Type.COMMA) {
                    // continue
                } else if (sep.type == Token.Type.GT) {
                    break
                } else if (sep.type == Token.Type.SHR) {
                    cc.pushPendingGT()
                    break
                } else {
                    sep.raiseSyntax("expected ',' or '>' in generic arguments")
                }
            } while (true)
            lastEnd = cc.currentPos()
        } else {
            cc.restorePos(afterBasePos)
        }

        val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
            true
        } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
            cc.pushPendingAssign()
            true
        } else false
        val endPos = cc.currentPos()

        val miniRef = buildBaseRef(if (miniArgs != null) endPos else lastEnd, miniArgs, isNullable)
        val sem = if (semArgs != null) TypeDecl.Generic(qualified, semArgs, isNullable)
        else TypeDecl.Simple(qualified, isNullable)
        return Pair(sem, miniRef)
    }

    private fun typeDeclToTypeRef(typeDecl: TypeDecl, pos: Pos): ObjRef {
        return when (typeDecl) {
            TypeDecl.TypeAny,
            TypeDecl.TypeNullableAny -> ConstRef(Obj.rootObjectType.asReadonly)
            is TypeDecl.TypeVar -> resolveLocalTypeRef(typeDecl.name, pos) ?: ConstRef(Obj.rootObjectType.asReadonly)
            else -> {
                val name = typeDeclName(typeDecl)
                resolveLocalTypeRef(name, pos)?.let { return it }
                val cls = resolveTypeDeclObjClass(typeDecl)
                if (cls != null) return ConstRef(cls.asReadonly)
                throw ScriptError(pos, "unknown type $name")
            }
        }
    }

    private fun typeDeclName(typeDecl: TypeDecl): String = when (typeDecl) {
        is TypeDecl.Simple -> typeDecl.name
        is TypeDecl.Generic -> typeDecl.name
        is TypeDecl.Function -> "Callable"
        is TypeDecl.Ellipsis -> "${typeDeclName(typeDecl.elementType)}..."
        is TypeDecl.TypeVar -> typeDecl.name
        is TypeDecl.Union -> typeDecl.options.joinToString(" | ") { typeDeclName(it) }
        is TypeDecl.Intersection -> typeDecl.options.joinToString(" & ") { typeDeclName(it) }
        TypeDecl.TypeAny -> "Object"
        TypeDecl.TypeNullableAny -> "Object?"
    }

    private fun inferTypeDeclFromInitializer(stmt: Statement): TypeDecl? {
        val directRef = unwrapDirectRef(stmt) ?: return null
        return inferTypeDeclFromRef(directRef)
    }

    private fun inferTypeDeclFromRef(ref: ObjRef): TypeDecl? {
        resolveReceiverTypeDecl(ref)?.let { return it }
        return when (ref) {
            is ListLiteralRef -> inferListLiteralTypeDecl(ref)
            is MapLiteralRef -> inferMapLiteralTypeDecl(ref)
            is ConstRef -> inferTypeDeclFromConst(ref.constValue)
            is CallRef -> {
                val targetDecl = resolveReceiverTypeDecl(ref.target) ?: seedTypeDeclFromRef(ref.target)
                val targetName = when (val target = ref.target) {
                    is LocalVarRef -> target.name
                    is FastLocalVarRef -> target.name
                    is LocalSlotRef -> target.name
                    else -> null
                }
                if (targetDecl is TypeDecl.Function) {
                    return inferCallReturnTypeDecl(ref) ?: targetDecl.returnType
                }
                inferCallReturnTypeDecl(ref)?.let { return it }
                if (targetName != null) {
                    callableReturnTypeDeclByName[targetName]?.let { return it }
                    (seedTypeDeclByName(targetName) as? TypeDecl.Function)?.let { return it.returnType }
                }
                inferCallReturnClass(ref)?.let { TypeDecl.Simple(it.className, false) }
                    ?: run {
                        if (targetName != null && targetName.firstOrNull()?.isUpperCase() == true) {
                            TypeDecl.Simple(targetName, false)
                        } else {
                            null
                        }
                    }
            }
            else -> null
        }
    }

    private fun inferTypeDeclFromConst(value: Obj): TypeDecl? = when (value) {
        is ObjInt -> TypeDecl.Simple("Int", false)
        is ObjReal -> TypeDecl.Simple("Real", false)
        is ObjString -> TypeDecl.Simple("String", false)
        is ObjBool -> TypeDecl.Simple("Bool", false)
        is ObjChar -> TypeDecl.Simple("Char", false)
        is ObjNull -> TypeDecl.TypeNullableAny
        is ObjList -> TypeDecl.Generic("List", listOf(TypeDecl.TypeAny), false)
        is ObjMap -> TypeDecl.Generic("Map", listOf(TypeDecl.TypeAny, TypeDecl.TypeAny), false)
        else -> null
    }

    private fun inferListLiteralTypeDecl(ref: ListLiteralRef): TypeDecl {
        val elementType = inferListLiteralElementType(ref.entries())
        return TypeDecl.Generic("List", listOf(elementType), false)
    }

    private fun inferMapLiteralTypeDecl(ref: MapLiteralRef): TypeDecl {
        val (keyType, valueType) = inferMapLiteralEntryTypes(ref.entries())
        return TypeDecl.Generic("Map", listOf(keyType, valueType), false)
    }

    private fun inferListLiteralElementType(entries: List<ListEntry>): TypeDecl {
        var nullable = false
        val collected = mutableListOf<TypeDecl>()
        val seen = mutableSetOf<String>()

        fun addType(type: TypeDecl) {
            val (base, isNullable) = stripNullable(type)
            nullable = nullable || isNullable
            if (base == TypeDecl.TypeAny) {
                collected.clear()
                collected += base
                seen.clear()
                seen += typeDeclKey(base)
                return
            }
            val key = typeDeclKey(base)
            if (seen.add(key)) {
                collected += base
            }
        }

        for (entry in entries) {
            val type = when (entry) {
                is ListEntry.Element -> inferTypeDeclFromRef(entry.ref)
                is ListEntry.Spread -> inferElementTypeFromSpread(entry.ref)
            } ?: return if (nullable) TypeDecl.TypeNullableAny else TypeDecl.TypeAny
            addType(type)
            if (collected.size == 1 && collected[0] == TypeDecl.TypeAny) break
        }

        if (collected.isEmpty()) return TypeDecl.TypeAny
        val base = if (collected.size == 1) {
            collected[0]
        } else {
            TypeDecl.Union(collected.toList(), nullable = false)
        }
        return if (nullable) makeTypeDeclNullable(base) else base
    }

    private fun inferMapLiteralEntryTypes(entries: List<MapLiteralEntry>): Pair<TypeDecl, TypeDecl> {
        var keyNullable = false
        var valueNullable = false
        val keyTypes = mutableListOf<TypeDecl>()
        val valueTypes = mutableListOf<TypeDecl>()
        val seenKeys = mutableSetOf<String>()
        val seenValues = mutableSetOf<String>()

        fun addKey(type: TypeDecl) {
            val (base, isNullable) = stripNullable(type)
            keyNullable = keyNullable || isNullable
            if (base == TypeDecl.TypeAny) {
                keyTypes.clear()
                keyTypes += base
                seenKeys.clear()
                seenKeys += typeDeclKey(base)
                return
            }
            val key = typeDeclKey(base)
            if (seenKeys.add(key)) keyTypes += base
        }

        fun addValue(type: TypeDecl) {
            val (base, isNullable) = stripNullable(type)
            valueNullable = valueNullable || isNullable
            if (base == TypeDecl.TypeAny) {
                valueTypes.clear()
                valueTypes += base
                seenValues.clear()
                seenValues += typeDeclKey(base)
                return
            }
            val key = typeDeclKey(base)
            if (seenValues.add(key)) valueTypes += base
        }

        for (entry in entries) {
            when (entry) {
                is MapLiteralEntry.Named -> {
                    addKey(TypeDecl.Simple("String", false))
                    val vType = inferTypeDeclFromRef(entry.value) ?: return TypeDecl.TypeAny to TypeDecl.TypeAny
                    addValue(vType)
                }
                is MapLiteralEntry.Spread -> {
                    val mapType = inferTypeDeclFromRef(entry.ref) ?: return TypeDecl.TypeAny to TypeDecl.TypeAny
                    if (mapType is TypeDecl.Generic) {
                        val base = mapType.name.substringAfterLast('.')
                        if (base == "Map" || base == "ImmutableMap") {
                            val k = mapType.args.getOrNull(0) ?: TypeDecl.TypeAny
                            val v = mapType.args.getOrNull(1) ?: TypeDecl.TypeAny
                            addKey(k)
                            addValue(v)
                        } else {
                            return TypeDecl.TypeAny to TypeDecl.TypeAny
                        }
                    } else {
                        return TypeDecl.TypeAny to TypeDecl.TypeAny
                    }
                }
            }
        }

        val keyBase = when {
            keyTypes.isEmpty() -> TypeDecl.TypeAny
            keyTypes.size == 1 -> keyTypes[0]
            else -> TypeDecl.Union(keyTypes.toList(), nullable = false)
        }
        val valueBase = when {
            valueTypes.isEmpty() -> TypeDecl.TypeAny
            valueTypes.size == 1 -> valueTypes[0]
            else -> TypeDecl.Union(valueTypes.toList(), nullable = false)
        }
        val finalKey = if (keyNullable) makeTypeDeclNullable(keyBase) else keyBase
        val finalValue = if (valueNullable) makeTypeDeclNullable(valueBase) else valueBase
        return finalKey to finalValue
    }

    private fun inferElementTypeFromSpread(ref: ObjRef): TypeDecl? {
        val listType = inferTypeDeclFromRef(ref) ?: return null
        if (listType == TypeDecl.TypeAny || listType == TypeDecl.TypeNullableAny) return listType
        if (listType is TypeDecl.Generic) {
            val base = listType.name.substringAfterLast('.')
            if (base == "List" || base == "ImmutableList" || base == "Array" || base == "Iterable") {
                return listType.args.firstOrNull() ?: TypeDecl.TypeAny
            }
        }
        return TypeDecl.TypeAny
    }

    private fun inferCollectionElementType(typeDecl: TypeDecl): TypeDecl? {
        val generic = typeDecl as? TypeDecl.Generic ?: return null
        val base = generic.name.substringAfterLast('.')
        return when (base) {
            "Set", "ImmutableSet", "List", "ImmutableList", "Iterable", "Collection", "Array" -> generic.args.firstOrNull()
            else -> null
        }
    }

    private fun inferForLoopElementType(source: Statement, constRange: ConstIntRange?): TypeDecl? {
        if (constRange != null) return TypeDecl.Simple("Int", false)
        val sourceType = inferTypeDeclFromInitializer(source) ?: return null
        return when {
            isRangeType(sourceType) -> TypeDecl.Simple("Int", false)
            else -> inferCollectionElementType(expandTypeAliases(sourceType, source.pos))
        }
    }

    private fun typeDeclSubtypeOf(arg: TypeDecl, param: TypeDecl): Boolean {
        if (param == TypeDecl.TypeAny || param == TypeDecl.TypeNullableAny) return true
        val (argBase, argNullable) = stripNullable(arg)
        val (paramBase, paramNullable) = stripNullable(param)
        if (argNullable && !paramNullable) return false
        if (paramBase == TypeDecl.TypeAny) return true
        if (paramBase is TypeDecl.TypeVar) return true
        if (argBase is TypeDecl.TypeVar) return true
        if (paramBase is TypeDecl.Simple && (paramBase.name == "Object" || paramBase.name == "Obj")) return true
        if (argBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase.elementType, paramBase)
        if (paramBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase, paramBase.elementType)
        return when (argBase) {
            is TypeDecl.Union -> argBase.options.all { typeDeclSubtypeOf(it, paramBase) }
            is TypeDecl.Intersection -> argBase.options.any { typeDeclSubtypeOf(it, paramBase) }
            else -> when (paramBase) {
                is TypeDecl.Union -> paramBase.options.any { typeDeclSubtypeOf(argBase, it) }
                is TypeDecl.Intersection -> paramBase.options.all { typeDeclSubtypeOf(argBase, it) }
                else -> {
                    val argClass = resolveTypeDeclObjClass(argBase) ?: return false
                    val paramClass = resolveTypeDeclObjClass(paramBase) ?: return false
                    argClass == paramClass || argClass.allParentsSet.contains(paramClass)
                }
            }
        }
    }

    private fun checkCollectionPlusAssignTypes(targetRef: ObjRef, valueRef: ObjRef, pos: Pos) {
        // Enforce strict compile-time element checks for declared members.
        // Local vars can be inferred from literals and are allowed to widen dynamically.
        if (targetRef !is FieldRef) return
        val targetDeclRaw = resolveReceiverTypeDecl(targetRef) ?: return
        val targetDecl = expandTypeAliases(targetDeclRaw, pos)
        val targetGeneric = targetDecl as? TypeDecl.Generic ?: return
        val targetBase = targetGeneric.name.substringAfterLast('.')
        if (targetBase != "Set" && targetBase != "List") return
        val elementRaw = targetGeneric.args.firstOrNull() ?: return
        val elementDecl = expandTypeAliases(elementRaw, pos)
        val valueDeclRaw = inferTypeDeclFromRef(valueRef) ?: return
        val valueDecl = expandTypeAliases(valueDeclRaw, pos)

        if (typeDeclSubtypeOf(valueDecl, elementDecl)) return

        val sourceElementDecl = inferCollectionElementType(valueDecl)?.let { expandTypeAliases(it, pos) }
        if (sourceElementDecl != null && typeDeclSubtypeOf(sourceElementDecl, elementDecl)) return

        throw ScriptError(
            pos,
            "argument type ${typeDeclName(valueDecl)} does not match ${typeDeclName(elementDecl)} for '+='"
        )
    }

    private fun stripNullable(type: TypeDecl): Pair<TypeDecl, Boolean> {
        if (type is TypeDecl.TypeNullableAny) return TypeDecl.TypeAny to true
        val nullable = type.isNullable
        val base = if (!nullable) type else when (type) {
            is TypeDecl.Function -> type.copy(nullable = false)
            is TypeDecl.Ellipsis -> type.copy(nullable = false)
            is TypeDecl.TypeVar -> type.copy(nullable = false)
            is TypeDecl.Union -> type.copy(nullable = false)
            is TypeDecl.Intersection -> type.copy(nullable = false)
            is TypeDecl.Simple -> TypeDecl.Simple(type.name, false)
            is TypeDecl.Generic -> TypeDecl.Generic(type.name, type.args, false)
            else -> type
        }
        return base to nullable
    }

    private fun typeDeclKey(type: TypeDecl): String = when (type) {
        TypeDecl.TypeAny -> "Any"
        TypeDecl.TypeNullableAny -> "Any?"
        is TypeDecl.Simple -> "S:${type.name}"
        is TypeDecl.Generic -> "G:${type.name}<${type.args.joinToString(",") { typeDeclKey(it) }}>"
        is TypeDecl.Function -> "F:(${type.params.joinToString(",") { typeDeclKey(it) }})->${typeDeclKey(type.returnType)}"
        is TypeDecl.Ellipsis -> "E:${typeDeclKey(type.elementType)}"
        is TypeDecl.TypeVar -> "V:${type.name}"
        is TypeDecl.Union -> "U:${type.options.joinToString("|") { typeDeclKey(it) }}"
        is TypeDecl.Intersection -> "I:${type.options.joinToString("&") { typeDeclKey(it) }}"
    }

    private fun classMethodReturnTypeDecl(targetClass: ObjClass?, name: String): TypeDecl? {
        if (targetClass == null) return null
        if (targetClass == ObjDynamic.type) return TypeDecl.TypeAny
        val member = targetClass.getInstanceMemberOrNull(name, includeAbstract = true)
        val declaringName = member?.declaringClass?.className
        if (declaringName != null) {
            classMethodReturnTypeDeclByName[declaringName]?.get(name)?.let { return it }
            classMethodReturnTypeByName[declaringName]?.get(name)?.let {
                return TypeDecl.Simple(it.className, false)
            }
        }
        classMethodReturnTypeDeclByName[targetClass.className]?.get(name)?.let { return it }
        classMethodReturnTypeByName[targetClass.className]?.get(name)?.let {
            return TypeDecl.Simple(it.className, false)
        }
        member?.typeDecl?.let { declaredType ->
            if (declaredType is TypeDecl.Function) return declaredType.returnType
            return declaredType
        }
        return null
    }

    private fun classMethodReturnClass(targetClass: ObjClass?, name: String): ObjClass? {
        if (targetClass == null) return null
        if (targetClass == ObjDynamic.type) return ObjDynamic.type
        classMethodReturnTypeDecl(targetClass, name)?.let { declared ->
            resolveTypeDeclObjClass(declared)?.let { return it }
            if (declared is TypeDecl.TypeVar) return Obj.rootObjectType
        }
        val member = targetClass.getInstanceMemberOrNull(name, includeAbstract = true)
        val declaringName = member?.declaringClass?.className
        if (declaringName != null) {
            classMethodReturnTypeByName[declaringName]?.get(name)?.let { return it }
        }
        classMethodReturnTypeByName[targetClass.className]?.get(name)?.let { return it }
        val declaredType = member?.typeDecl
        if (declaredType is TypeDecl.Function) {
            resolveTypeDeclObjClass(declaredType.returnType)?.let { return it }
        }
        return null
    }

    private fun inferObjClassFromRef(ref: ObjRef): ObjClass? = when (ref) {
        is ConstRef -> ref.constValue as? ObjClass ?: ref.constValue.objClass
        is LocalVarRef -> nameObjClass[ref.name] ?: resolveClassByName(ref.name)
        is FastLocalVarRef -> nameObjClass[ref.name] ?: resolveClassByName(ref.name)
        is LocalSlotRef -> {
            val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
            val ownerSlot = ref.captureOwnerSlot ?: ref.slot
            slotTypeByScopeId[ownerScopeId]?.get(ownerSlot)
                ?: nameObjClass[ref.name]
                ?: resolveClassByName(ref.name)
        }
        is ClassScopeMemberRef -> {
            val targetClass = resolveClassByName(ref.ownerClassName()) ?: return null
            inferFieldReturnClass(targetClass, ref.name)
        }
        is ListLiteralRef -> ObjList.type
        is MapLiteralRef -> ObjMap.type
        is RangeRef -> ObjRange.type
        is ClassOperatorRef -> ObjClassType
        is CastRef -> resolveTypeRefClass(ref.castTypeRef())
        is IndexRef -> {
            val targetClass = resolveReceiverClassForMember(ref.targetRef)
            classMethodReturnClass(targetClass, "getAt")
                ?: inferFieldReturnClass(targetClass, "getAt")
        }
        else -> null
    }

    private fun seedTypeDeclByName(name: String): TypeDecl? {
        seedScope?.getLocalRecordDirect(name)?.typeDecl?.let { return it }
        seedScope?.get(name)?.typeDecl?.let { return it }
        importManager.rootScope.getLocalRecordDirect(name)?.typeDecl?.let { return it }
        importManager.rootScope.get(name)?.typeDecl?.let { return it }
        for (module in importedModules.asReversed()) {
            module.scope.get(name)?.typeDecl?.let { return it }
        }
        return null
    }

    private fun lookupLocalTypeDeclByName(name: String): TypeDecl? {
        val slotLoc = lookupSlotLocation(name, includeModule = true) ?: return null
        return slotTypeDeclByScopeId[slotLoc.scopeId]?.get(slotLoc.slot)
    }

    private fun lookupLocalObjClassByName(name: String): ObjClass? {
        val slotLoc = lookupSlotLocation(name, includeModule = true) ?: return null
        return slotTypeByScopeId[slotLoc.scopeId]?.get(slotLoc.slot)
            ?: slotTypeDeclByScopeId[slotLoc.scopeId]?.get(slotLoc.slot)?.let { resolveTypeDeclObjClass(it) }
    }

    private fun resolveReceiverTypeDecl(ref: ObjRef): TypeDecl? {
        return when (ref) {
            is LocalSlotRef -> {
                val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
                val ownerSlot = ref.captureOwnerSlot ?: ref.slot
                slotTypeDeclByScopeId[ownerScopeId]?.get(ownerSlot)
                    ?: nameTypeDecl[ref.name]
                    ?: seedTypeDeclByName(ref.name)
            }
            is LocalVarRef -> nameTypeDecl[ref.name]
                ?: lookupLocalTypeDeclByName(ref.name)
                ?: seedTypeDeclByName(ref.name)
            is FastLocalVarRef -> nameTypeDecl[ref.name]
                ?: lookupLocalTypeDeclByName(ref.name)
                ?: seedTypeDeclByName(ref.name)
            is FieldRef -> {
                val targetDecl = resolveReceiverTypeDecl(ref.target) ?: return null
                val targetClass = resolveTypeDeclObjClass(targetDecl) ?: resolveReceiverClassForMember(ref.target)
                targetClass?.getInstanceMemberOrNull(ref.name, includeAbstract = true)?.typeDecl?.let { return it }
                classFieldTypesByName[targetClass?.className]?.get(ref.name)
                    ?.let { return TypeDecl.Simple(it.className, false) }
                when (targetDecl) {
                    TypeDecl.TypeAny, TypeDecl.TypeNullableAny -> null
                    else -> TypeDecl.TypeVar("${typeDeclName(targetDecl)}.${ref.name}", false)
                }
            }
            is IndexRef -> {
                val targetDecl = resolveReceiverTypeDecl(ref.targetRef)
                inferMethodCallReturnTypeDecl("getAt", targetDecl)?.let { return it }
                val targetClass = resolveReceiverClassForMember(ref.targetRef)
                classMethodReturnTypeDecl(targetClass, "getAt")
            }
            is MethodCallRef -> methodReturnTypeDeclByRef[ref]
            is CallRef -> callReturnTypeDeclByRef[ref] ?: inferCallReturnTypeDecl(ref)
            is BinaryOpRef -> inferBinaryOpReturnTypeDecl(ref)
            is StatementRef -> (ref.statement as? ExpressionStatement)?.let { resolveReceiverTypeDecl(it.ref) }
            else -> null
        }
    }

    private fun resolveReceiverClassForMember(ref: ObjRef): ObjClass? {
        return when (ref) {
            is LocalSlotRef -> {
                val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
                val ownerSlot = ref.captureOwnerSlot ?: ref.slot
                val knownClass = nameObjClass[ref.name]
                if (knownClass == ObjDynamic.type) {
                    knownClass
                } else {
                    slotTypeByScopeId[ownerScopeId]?.get(ownerSlot)
                        ?: slotTypeDeclByScopeId[ownerScopeId]?.get(ownerSlot)?.let { resolveTypeDeclObjClass(it) }
                        ?: nameTypeDecl[ref.name]?.let { resolveTypeDeclObjClass(it) }
                        ?: seedTypeDeclByName(ref.name)?.let { resolveTypeDeclObjClass(it) }
                        ?: knownClass
                }
                    ?: resolveClassByName(ref.name)
            }
            is LocalVarRef -> nameObjClass[ref.name]
                ?.takeIf { it == ObjDynamic.type }
                ?: nameObjClass[ref.name]
                ?: lookupLocalObjClassByName(ref.name)
                ?: nameTypeDecl[ref.name]?.let { resolveTypeDeclObjClass(it) }
                ?: resolveClassByName(ref.name)
            is FastLocalVarRef -> nameObjClass[ref.name]
                ?.takeIf { it == ObjDynamic.type }
                ?: nameObjClass[ref.name]
                ?: lookupLocalObjClassByName(ref.name)
                ?: nameTypeDecl[ref.name]?.let { resolveTypeDeclObjClass(it) }
                ?: resolveClassByName(ref.name)
            is ClassScopeMemberRef -> {
                val targetClass = resolveClassByName(ref.ownerClassName())
                inferFieldReturnClass(targetClass, ref.name)
            }
            is ImplicitThisMemberRef -> {
                val typeName = ref.preferredThisTypeName() ?: currentImplicitThisTypeName()
                val targetClass = typeName?.let { resolveClassByName(it) }
                inferFieldReturnClass(targetClass, ref.name)
            }
            is ThisFieldSlotRef -> {
                val typeName = currentImplicitThisTypeName()
                val targetClass = typeName?.let { resolveClassByName(it) }
                inferFieldReturnClass(targetClass, ref.name)
            }
            is QualifiedThisFieldSlotRef -> {
                val targetClass = resolveClassByName(ref.receiverTypeName())
                inferFieldReturnClass(targetClass, ref.name)
            }
            is ConstRef -> ref.constValue as? ObjClass ?: ref.constValue.objClass
            is ListLiteralRef -> ObjList.type
            is MapLiteralRef -> ObjMap.type
            is RangeRef -> ObjRange.type
            is ClassOperatorRef -> ObjClassType
            is CastRef -> resolveTypeRefClass(ref.castTypeRef())
            is QualifiedThisRef -> resolveClassByName(ref.typeName)
            is StatementRef -> (ref.statement as? ExpressionStatement)?.let { resolveReceiverClassForMember(it.ref) }
            is MethodCallRef -> inferMethodCallReturnClass(ref)
            is ImplicitThisMethodCallRef -> inferMethodCallReturnClass(ref.methodName())
            is ThisMethodSlotCallRef -> inferMethodCallReturnClass(ref.methodName())
            is QualifiedThisMethodSlotCallRef -> inferMethodCallReturnClass(ref.methodName())
            is CallRef -> inferCallReturnTypeDecl(ref)?.let { resolveTypeDeclObjClass(it) } ?: inferCallReturnClass(ref)
            is BinaryOpRef -> inferBinaryOpReturnClass(ref)
            is FieldRef -> {
                val targetClass = resolveReceiverClassForMember(ref.target)
                inferFieldReturnClass(targetClass, ref.name)
            }
            is IndexRef -> {
                val targetClass = resolveReceiverClassForMember(ref.targetRef)
                classMethodReturnClass(targetClass, "getAt")
                    ?: inferFieldReturnClass(targetClass, "getAt")
                    ?: inferMethodCallReturnClass("getAt")
            }
            else -> null
        }
    }

    private fun typeDeclOfClass(objClass: ObjClass): TypeDecl = TypeDecl.Simple(objClass.className, false)

    private fun binaryOpMethodName(op: BinOp): String? = when (op) {
        BinOp.PLUS -> "plus"
        BinOp.MINUS -> "minus"
        BinOp.STAR -> "mul"
        BinOp.SLASH -> "div"
        BinOp.PERCENT -> "mod"
        else -> null
    }

    private fun interopOperatorFor(op: BinOp): InteropOperator? = when (op) {
        BinOp.PLUS -> InteropOperator.Plus
        BinOp.MINUS -> InteropOperator.Minus
        BinOp.STAR -> InteropOperator.Mul
        BinOp.SLASH -> InteropOperator.Div
        BinOp.PERCENT -> InteropOperator.Mod
        BinOp.LT, BinOp.LTE, BinOp.GT, BinOp.GTE -> InteropOperator.Compare
        BinOp.EQ, BinOp.NEQ -> InteropOperator.Equals
        else -> null
    }

    private fun sameClassArithmeticFallback(leftClass: ObjClass, rightClass: ObjClass, op: BinOp): TypeDecl? {
        if (leftClass !== rightClass) return null
        val methodName = binaryOpMethodName(op) ?: return null
        if (leftClass.getInstanceMemberOrNull(methodName, includeAbstract = true) == null) return null
        return typeDeclOfClass(leftClass)
    }

    private fun inferBinaryOpReturnTypeDecl(ref: BinaryOpRef): TypeDecl? {
        val leftClass = resolveReceiverClassForMember(ref.left) ?: inferObjClassFromRef(ref.left)
        val rightClass = resolveReceiverClassForMember(ref.right) ?: inferObjClassFromRef(ref.right)
        val boolType = typeDeclOfClass(ObjBool.type)
        val intType = typeDeclOfClass(ObjInt.type)
        val realType = typeDeclOfClass(ObjReal.type)
        val stringType = typeDeclOfClass(ObjString.type)

        when (ref.op) {
            BinOp.OR, BinOp.AND,
            BinOp.EQARROW, BinOp.EQ, BinOp.NEQ, BinOp.REF_EQ, BinOp.REF_NEQ, BinOp.MATCH, BinOp.NOTMATCH,
            BinOp.LTE, BinOp.LT, BinOp.GTE, BinOp.GT,
            BinOp.IN, BinOp.NOTIN,
            BinOp.IS, BinOp.NOTIS -> return boolType
            else -> {}
        }

        if (leftClass == null || rightClass == null) return null

        val leftIsInt = leftClass == ObjInt.type
        val rightIsInt = rightClass == ObjInt.type
        val leftIsReal = leftClass == ObjReal.type
        val rightIsReal = rightClass == ObjReal.type
        val leftIsNumeric = leftIsInt || leftIsReal
        val rightIsNumeric = rightIsInt || rightIsReal

        return when (ref.op) {
            BinOp.PLUS -> when {
                leftIsInt && rightIsInt -> intType
                leftIsNumeric && rightIsNumeric -> realType
                leftClass == ObjString.type -> stringType
                interopOperatorFor(ref.op)?.let {
                    OperatorInteropRegistry.commonClassFor(leftClass, rightClass, it)
                } != null -> typeDeclOfClass(
                    OperatorInteropRegistry.commonClassFor(leftClass, rightClass, interopOperatorFor(ref.op)!!)!!
                )
                else -> binaryOpMethodName(ref.op)?.let { classMethodReturnTypeDecl(leftClass, it) }
                    ?: sameClassArithmeticFallback(leftClass, rightClass, ref.op)
            }
            BinOp.MINUS, BinOp.STAR, BinOp.SLASH, BinOp.PERCENT -> when {
                leftIsInt && rightIsInt -> intType
                leftIsNumeric && rightIsNumeric -> realType
                ref.op == BinOp.STAR && leftClass == ObjString.type && rightIsNumeric -> stringType
                interopOperatorFor(ref.op)?.let {
                    OperatorInteropRegistry.commonClassFor(leftClass, rightClass, it)
                } != null -> typeDeclOfClass(
                    OperatorInteropRegistry.commonClassFor(leftClass, rightClass, interopOperatorFor(ref.op)!!)!!
                )
                else -> binaryOpMethodName(ref.op)?.let { classMethodReturnTypeDecl(leftClass, it) }
                    ?: sameClassArithmeticFallback(leftClass, rightClass, ref.op)
            }
            BinOp.BAND, BinOp.BOR, BinOp.BXOR, BinOp.SHL, BinOp.SHR ->
                if (leftIsInt && rightIsInt) intType else null
            else -> null
        }
    }

    private fun inferBinaryOpReturnClass(ref: BinaryOpRef): ObjClass? {
        inferBinaryOpReturnTypeDecl(ref)?.let { declared ->
            resolveTypeDeclObjClass(declared)?.let { return it }
        }
        val leftClass = resolveReceiverClassForMember(ref.left) ?: inferObjClassFromRef(ref.left)
        val rightClass = resolveReceiverClassForMember(ref.right) ?: inferObjClassFromRef(ref.right)
        if (leftClass == null || rightClass == null) return null
        interopOperatorFor(ref.op)?.let { op ->
            OperatorInteropRegistry.commonClassFor(leftClass, rightClass, op)?.let { return it }
        }
        sameClassArithmeticFallback(leftClass, rightClass, ref.op)?.let { declared ->
            resolveTypeDeclObjClass(declared)?.let { return it }
        }
        return when (ref.op) {
            BinOp.PLUS, BinOp.MINUS -> when {
                leftClass == ObjInstant.type && rightClass == ObjInstant.type && ref.op == BinOp.MINUS -> ObjDuration.type
                leftClass == ObjInstant.type && rightClass == ObjDuration.type -> ObjInstant.type
                leftClass == ObjDuration.type && rightClass == ObjInstant.type && ref.op == BinOp.PLUS -> ObjInstant.type
                leftClass == ObjDuration.type && rightClass == ObjDuration.type -> ObjDuration.type
                (leftClass == ObjBuffer.type || leftClass.allParentsSet.contains(ObjBuffer.type)) &&
                    (rightClass == ObjBuffer.type || rightClass.allParentsSet.contains(ObjBuffer.type)) &&
                    ref.op == BinOp.PLUS -> ObjBuffer.type
                else -> null
            }
            else -> null
        }
    }

    private fun inferCallReturnClass(ref: CallRef): ObjClass? {
        return when (val target = ref.target) {
            is LocalSlotRef -> when (target.name) {
                "lazy" -> resolveClassByName("lazy")
                "iterator" -> ObjIterator
                "flow" -> ObjFlow.type
                "launch" -> ObjDeferred.type
                "dynamic" -> ObjDynamic.type
                else -> callableReturnTypeByScopeId[target.scopeId]?.get(target.slot)
                    ?: resolveClassByName(target.name)
            }
            is LocalVarRef -> when (target.name) {
                "lazy" -> resolveClassByName("lazy")
                "iterator" -> ObjIterator
                "flow" -> ObjFlow.type
                "launch" -> ObjDeferred.type
                "dynamic" -> ObjDynamic.type
                else -> callableReturnTypeByName[target.name]
                    ?: resolveClassByName(target.name)
            }
            is FastLocalVarRef -> when (target.name) {
                "lazy" -> resolveClassByName("lazy")
                "iterator" -> ObjIterator
                "flow" -> ObjFlow.type
                "launch" -> ObjDeferred.type
                "dynamic" -> ObjDynamic.type
                else -> callableReturnTypeByName[target.name]
                    ?: resolveClassByName(target.name)
            }
            is ConstRef -> when (val value = target.constValue) {
                is ObjClass -> value
                is ObjString -> ObjString.type
                else -> null
            }
            is FieldRef -> {
                val receiverClass = resolveReceiverClassForMember(target.target) ?: return null
                if (!isClassScopeCallableMember(receiverClass.className, target.name)) return null
                resolveClassByName("${receiverClass.className}.${target.name}")
            }
            else -> null
        }
    }

    private fun inferMethodCallReturnClass(ref: MethodCallRef): ObjClass? {
        val receiverDecl = resolveReceiverTypeDecl(ref.receiver)
        val genericReturnDecl = inferMethodCallReturnTypeDecl(ref.name, receiverDecl)
        if (genericReturnDecl != null) {
            methodReturnTypeDeclByRef[ref] = genericReturnDecl
            resolveTypeDeclObjClass(genericReturnDecl)?.let { return it }
            if (genericReturnDecl is TypeDecl.TypeVar) {
                return Obj.rootObjectType
            }
        }
        if (ref.name == "decode") {
            val payload = inferEncodedPayloadClass(ref.args)
            if (payload != null) return payload
        }
        val receiverClass = resolveReceiverClassForMember(ref.receiver)
        classMethodReturnClass(receiverClass, ref.name)?.let { return it }
        inferFieldReturnClass(receiverClass, ref.name)?.let { return it }
        if (receiverClass != null && isClassScopeCallableMember(receiverClass.className, ref.name)) {
            resolveClassByName("${receiverClass.className}.${ref.name}")?.let { return it }
        }
        return inferMethodCallReturnClass(ref.name)
    }

    private fun inferMethodCallReturnTypeDecl(name: String, receiver: TypeDecl?): TypeDecl? {
        val base = when (receiver) {
            is TypeDecl.Generic -> receiver.name.substringAfterLast('.')
            is TypeDecl.Simple -> receiver.name.substringAfterLast('.')
            else -> null
        }
        return when {
            name == "iterator" && receiver is TypeDecl.Generic && base == "Iterable" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("Iterator", listOf(arg), false)
            }
            name == "next" && receiver is TypeDecl.Generic && base == "Iterator" -> {
                receiver.args.firstOrNull()
            }
            name == "toImmutableList" && receiver is TypeDecl.Generic && (base == "Iterable" || base == "Collection" || base == "Array" || base == "List" || base == "ImmutableList") -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("ImmutableList", listOf(arg), false)
            }
            name == "toList" && receiver is TypeDecl.Generic && (base == "ImmutableList" || base == "List") -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("List", listOf(arg), false)
            }
            name == "toMutable" && receiver is TypeDecl.Generic && base == "ImmutableList" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("List", listOf(arg), false)
            }
            name == "toImmutable" && receiver is TypeDecl.Generic && base == "List" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("ImmutableList", listOf(arg), false)
            }
            name == "toImmutableSet" && receiver is TypeDecl.Generic && (base == "Iterable" || base == "Collection" || base == "Set" || base == "ImmutableSet") -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("ImmutableSet", listOf(arg), false)
            }
            name == "toSet" && receiver is TypeDecl.Generic && (base == "Iterable" || base == "Collection" || base == "Set" || base == "ImmutableSet") -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("Set", listOf(arg), false)
            }
            name == "toMutable" && receiver is TypeDecl.Generic && base == "ImmutableSet" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("Set", listOf(arg), false)
            }
            name == "toImmutable" && receiver is TypeDecl.Generic && base == "Set" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("ImmutableSet", listOf(arg), false)
            }
            name == "toImmutableMap" && receiver is TypeDecl.Generic && base == "Iterable" -> {
                TypeDecl.Generic("ImmutableMap", listOf(TypeDecl.TypeAny, TypeDecl.TypeAny), false)
            }
            name == "observable" && receiver is TypeDecl.Generic && (base == "List" || base == "ObservableList") -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("ObservableList", listOf(arg), false)
            }
            name == "changes" && receiver is TypeDecl.Generic && base == "ObservableList" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("Flow", listOf(TypeDecl.Generic("ListChange", listOf(arg), false)), false)
            }
            name == "changes" && receiver is TypeDecl.Generic && base == "Observable" -> {
                val arg = receiver.args.firstOrNull() ?: TypeDecl.TypeAny
                TypeDecl.Generic("Flow", listOf(arg), false)
            }
            (name == "beforeChange" || name == "onChange") && receiver is TypeDecl.Generic &&
                (base == "Observable" || base == "ObservableList") -> {
                TypeDecl.Simple("Subscription", false)
            }
            name == "toMap" && receiver is TypeDecl.Generic && base == "Iterable" -> {
                TypeDecl.Generic("Map", listOf(TypeDecl.TypeAny, TypeDecl.TypeAny), false)
            }
            name == "toMutable" && receiver is TypeDecl.Generic && base == "ImmutableMap" -> {
                val args = receiver.args.ifEmpty { listOf(TypeDecl.TypeAny, TypeDecl.TypeAny) }
                TypeDecl.Generic("Map", args, false)
            }
            name == "toImmutable" && receiver is TypeDecl.Generic && base == "Map" -> {
                val args = receiver.args.ifEmpty { listOf(TypeDecl.TypeAny, TypeDecl.TypeAny) }
                TypeDecl.Generic("ImmutableMap", args, false)
            }
            name == "toImmutable" && base == "List" -> TypeDecl.Simple("ImmutableList", false)
            name == "toMutable" && base == "ImmutableList" -> TypeDecl.Simple("List", false)
            name == "toImmutable" && base == "Set" -> TypeDecl.Simple("ImmutableSet", false)
            name == "toMutable" && base == "ImmutableSet" -> TypeDecl.Simple("Set", false)
            name == "toImmutable" && base == "Map" -> TypeDecl.Simple("ImmutableMap", false)
            name == "toMutable" && base == "ImmutableMap" -> TypeDecl.Simple("Map", false)
            else -> null
        }
    }

    private fun inferEncodedPayloadClass(args: List<ParsedArgument>): ObjClass? {
        val stmt = args.firstOrNull()?.value as? ExpressionStatement ?: return null
        val ref = stmt.ref
        val byEncoded = when (ref) {
            is LocalSlotRef -> encodedPayloadTypeByScopeId[ref.scopeId]?.get(ref.slot)
            is LocalVarRef -> encodedPayloadTypeByName[ref.name]
            else -> null
        }
        if (byEncoded != null) return byEncoded
        return when (ref) {
            is LocalSlotRef -> {
                val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
                val ownerSlot = ref.captureOwnerSlot ?: ref.slot
                slotTypeByScopeId[ownerScopeId]?.get(ownerSlot)
            }
            is LocalVarRef -> nameObjClass[ref.name]
            is CastRef -> resolveTypeRefClass(ref.castTypeRef())
            is MethodCallRef -> if (ref.name == "encode") inferEncodedPayloadClass(ref.args) else null
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
        "toImmutableList" -> ObjImmutableList.type
        "toImmutableSet" -> ObjImmutableSet.type
        "toImmutableMap" -> ObjImmutableMap.type
        "observable" -> ObjObservableList.type
        "beforeChange", "onChange" -> ObjSubscription.type
        "changes" -> ObjFlow.type
        "toImmutable" -> Obj.rootObjectType
        "toMutable" -> ObjMutableBuffer.type
        "seq" -> ObjFlow.type
        "encode" -> ObjBitBuffer.type
        "assertThrows" -> ObjException.Root
        else -> null
    }

    private fun inferFieldReturnClass(targetClass: ObjClass?, name: String): ObjClass? {
        if (targetClass == null) return null
        if (targetClass == ObjDynamic.type) return ObjDynamic.type
        classFieldTypesByName[targetClass.className]?.get(name)?.let { return it }
        var hasUntypedMember = false
        targetClass.getInstanceMemberOrNull(name, includeAbstract = true)?.let { member ->
            member.typeDecl?.let { declaredType ->
                when (declaredType) {
                    TypeDecl.TypeAny, TypeDecl.TypeNullableAny -> return Obj.rootObjectType
                    else -> {
                        resolveTypeDeclObjClass(declaredType)?.let { return it }
                    }
                }
            }
            hasUntypedMember = true
        }
        enumEntriesByName[targetClass.className]?.let { entries ->
            return when {
                name == "entries" -> ObjList.type
                name == "name" -> ObjString.type
                name == "ordinal" -> ObjInt.type
                entries.contains(name) -> targetClass
                else -> null
            }
        }
        if (targetClass == ObjInstant.type && (name == "distantFuture" || name == "distantPast")) {
            return ObjInstant.type
        }
        if (targetClass == ObjInstant.type && name in listOf(
                "truncateToMinute",
                "truncateToSecond",
                "truncateToMillisecond",
                "truncateToMicrosecond"
            )
        ) {
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
                "epochSeconds" -> ObjInt.type
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
        if (hasUntypedMember) {
            return ObjDynamic.type
        }
        return null
    }

    private fun shouldTreatAsClassScopeCall(left: ObjRef, memberName: String): Boolean {
        val receiverClass = resolveReceiverClassForMember(left) ?: return false
        return isClassScopeCallableMember(receiverClass.className, memberName)
    }

    private fun enforceReceiverTypeForMember(left: ObjRef, memberName: String, pos: Pos) {
        if (left is LocalVarRef && left.name == "scope") return
        if (left is LocalSlotRef && left.name == "scope") return
        val receiverClass = resolveReceiverClassForMember(left)
        if (receiverClass == null) {
            val receiverDecl = resolveReceiverTypeDecl(left)
            if (receiverDecl != null && receiverDecl != TypeDecl.TypeAny && receiverDecl != TypeDecl.TypeNullableAny) {
                return
            }
            if (isAllowedObjectMember(memberName)) return
            throw ScriptError(pos, "member access requires compile-time receiver type: $memberName")
        }
        registerExtensionWrapperBindings(receiverClass, memberName, pos)
        if (receiverClass == Obj.rootObjectType) {
            val allowed = isAllowedObjectMember(memberName)
            if (!allowed && !hasExtensionFor(receiverClass.className, memberName)) {
                throw ScriptError(pos, "member $memberName is not available on Object without explicit cast")
            }
        }
    }

    private fun resolveMemberHostForUnion(typeDecl: TypeDecl, memberName: String, pos: Pos): ObjClass {
        val receiverClass = when (typeDecl) {
            TypeDecl.TypeAny, TypeDecl.TypeNullableAny -> Obj.rootObjectType
            else -> resolveTypeDeclObjClass(typeDecl)
        } ?: throw ScriptError(pos, "member access requires compile-time receiver type: $memberName")
        if (receiverClass == ObjDynamic.type) {
            return receiverClass
        }
        registerExtensionWrapperBindings(receiverClass, memberName, pos)
        val hasMember = receiverClass.instanceFieldIdMap()[memberName] != null ||
            receiverClass.instanceMethodIdMap(includeAbstract = true)[memberName] != null
        if (!hasMember && !hasExtensionFor(receiverClass.className, memberName)) {
            if (receiverClass == Obj.rootObjectType && isAllowedObjectMember(memberName)) {
                return receiverClass
            }
            val ownerName = receiverClass.className
            val message = if (receiverClass == Obj.rootObjectType) {
                "member $memberName is not available on Object without explicit cast"
            } else {
                "unknown member $memberName on $ownerName"
            }
            throw ScriptError(pos, message)
        }
        return receiverClass
    }

    private fun buildUnionMemberAccess(
        left: ObjRef,
        union: TypeDecl.Union,
        memberName: String,
        pos: Pos,
        isOptional: Boolean,
        makeRef: (ObjRef) -> ObjRef
    ): ObjRef {
        val options = union.options
        if (options.isEmpty()) {
            throw ScriptError(pos, "member access requires compile-time receiver type: $memberName")
        }
        for (option in options) {
            resolveMemberHostForUnion(option, memberName, pos)
        }
        val unionName = typeDeclName(union)
        val errorMessage = ObjString("value is not $unionName").asReadonly
        val throwExpr = ExpressionStatement(ConstRef(errorMessage), pos)
        val throwStmt = ThrowStatement(throwExpr, pos)
        var current: ObjRef = net.sergeych.lyng.obj.StatementRef(throwStmt)
        for (option in options.asReversed()) {
            val typeRef = net.sergeych.lyng.obj.TypeDeclRef(option, pos)
            val cond = BinaryOpRef(BinOp.IS, left, typeRef)
            val casted = CastRef(left, typeRef, false, pos)
            val branch = makeRef(casted)
            current = ConditionalRef(cond, branch, current)
        }
        if (isOptional) {
            val nullRef = ConstRef(ObjNull.asReadonly)
            val nullCond = BinaryOpRef(BinOp.REF_EQ, left, nullRef)
            current = ConditionalRef(nullCond, nullRef, current)
        }
        return current
    }

    private fun buildUnionFieldAccess(
        left: ObjRef,
        memberName: String,
        pos: Pos,
        isOptional: Boolean
    ): ObjRef? {
        val receiverDecl = resolveReceiverTypeDecl(left) as? TypeDecl.Union ?: return null
        return buildUnionMemberAccess(left, receiverDecl, memberName, pos, isOptional) { receiver ->
            FieldRef(receiver, memberName, false)
        }
    }

    private fun buildUnionMethodCall(
        left: ObjRef,
        memberName: String,
        pos: Pos,
        isOptional: Boolean,
        args: List<ParsedArgument>,
        tailBlock: Boolean
    ): ObjRef? {
        val receiverDecl = resolveReceiverTypeDecl(left) as? TypeDecl.Union ?: return null
        return buildUnionMemberAccess(left, receiverDecl, memberName, pos, isOptional) { receiver ->
            MethodCallRef(receiver, memberName, args, tailBlock, false)
        }
    }

    private fun registerExtensionWrapperBindings(receiverClass: ObjClass, memberName: String, pos: Pos) {
        for (cls in receiverClass.mro) {
            val wrapperNames = listOf(
                extensionCallableName(cls.className, memberName),
                extensionPropertyGetterName(cls.className, memberName),
                extensionPropertySetterName(cls.className, memberName)
            )
            for (wrapperName in wrapperNames) {
                val resolved = resolveImportBinding(wrapperName, pos) ?: continue
                val plan = moduleSlotPlan()
                if (plan != null && !plan.slots.containsKey(wrapperName)) {
                    declareSlotNameIn(
                        plan,
                        wrapperName,
                        resolved.record.isMutable,
                        resolved.record.type == ObjRecord.Type.Delegated
                    )
                }
                registerImportBinding(wrapperName, resolved.binding, pos)
            }
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

    private fun resolveTypeRefClass(ref: ObjRef): ObjClass? = when (ref) {
        is TypeDeclRef -> resolveTypeDeclObjClass(ref.decl())
        is ConstRef -> ref.constValue as? ObjClass
        is LocalSlotRef -> resolveTypeDeclObjClass(TypeDecl.Simple(ref.name, false)) ?: nameObjClass[ref.name]
        is LocalVarRef -> resolveTypeDeclObjClass(TypeDecl.Simple(ref.name, false)) ?: nameObjClass[ref.name]
        is FastLocalVarRef -> resolveTypeDeclObjClass(TypeDecl.Simple(ref.name, false)) ?: nameObjClass[ref.name]
        else -> null
    }

    private fun typeParamBoundSatisfied(argClass: ObjClass, bound: TypeDecl): Boolean = when (bound) {
        is TypeDecl.Union -> bound.options.any { typeParamBoundSatisfied(argClass, it) }
        is TypeDecl.Intersection -> bound.options.all { typeParamBoundSatisfied(argClass, it) }
        is TypeDecl.Simple, is TypeDecl.Generic -> {
            val boundClass = resolveTypeDeclObjClass(bound) ?: return false
            argClass == boundClass || argClass.allParentsSet.contains(boundClass)
        }
        else -> true
    }

    private fun checkGenericBoundsAtCall(
        name: String,
        args: List<ParsedArgument>,
        pos: Pos
    ) {
        val decl = lookupGenericFunctionDecl(name) ?: return
        val inferred = mutableMapOf<String, TypeDecl>()
        val limit = minOf(args.size, decl.params.size)
        for (i in 0 until limit) {
            val paramType = decl.params[i].type
            val argRef = (args[i].value as? ExpressionStatement)?.ref ?: continue
            val argTypeDecl = inferTypeDeclFromRef(argRef)
                ?: inferObjClassFromRef(argRef)?.let { TypeDecl.Simple(it.className, false) }
                ?: continue
            collectTypeVarBindings(paramType, argTypeDecl, inferred)
        }
        for (tp in decl.typeParams) {
            val argType = inferred[tp.name] ?: continue
            val bound = tp.bound ?: continue
            if (!typeDeclSatisfiesBound(argType, bound)) {
                throw ScriptError(pos, "type argument ${typeDeclName(argType)} does not satisfy bound ${typeDeclName(bound)}")
            }
        }
    }

    private fun checkFunctionTypeCallArity(
        target: ObjRef,
        args: List<ParsedArgument>,
        pos: Pos
    ) {
        lookupNamedFunctionDecl(target)?.let { decl ->
            val hasComplexArgs = args.any { it.name != null } ||
                decl.typeParams.isNotEmpty() ||
                decl.params.any { it.defaultValue != null || it.isEllipsis }
            if (hasComplexArgs) return
            if (args.any { it.isSplat }) return
            val actual = args.size
            val params = decl.params
            val ellipsisIndex = params.indexOfFirst { it.isEllipsis }
            if (ellipsisIndex < 0) {
                val minArgs = params.count { it.defaultValue == null }
                val maxArgs = params.size
                if (actual < minArgs || actual > maxArgs) {
                    val message = if (minArgs == maxArgs) {
                        "expected $maxArgs arguments, got $actual"
                    } else {
                        "expected $minArgs..$maxArgs arguments, got $actual"
                    }
                    throw ScriptError(pos, message)
                }
                return
            }
            val headRequired = (0 until ellipsisIndex).count { params[it].defaultValue == null }
            val tailRequired = (ellipsisIndex + 1 until params.size).count { params[it].defaultValue == null }
            val minArgs = headRequired + tailRequired
            if (actual < minArgs) {
                throw ScriptError(pos, "expected at least $minArgs arguments, got $actual")
            }
            return
        }
        val seededCallable = lookupNamedCallableRecord(target)
        if (seededCallable != null && seededCallable.type == ObjRecord.Type.Fun && seededCallable.value !is ObjExternCallable) {
            return
        }
        val decl = (resolveReceiverTypeDecl(target) as? TypeDecl.Function)
            ?: seedTypeDeclFromRef(target) as? TypeDecl.Function
            ?: return
        if (args.any { it.isSplat }) return
        val actual = args.size
        val paramList = mutableListOf<TypeDecl>()
        decl.receiver?.let { paramList += it }
        paramList += decl.params
        val ellipsisIndex = paramList.indexOfFirst { it is TypeDecl.Ellipsis }
        if (ellipsisIndex < 0) {
            val expected = paramList.size
            if (actual != expected) {
                throw ScriptError(pos, "expected $expected arguments, got $actual")
            }
            return
        }
        val headCount = ellipsisIndex
        val tailCount = paramList.size - ellipsisIndex - 1
        val minArgs = headCount + tailCount
        if (actual < minArgs) {
            throw ScriptError(pos, "expected at least $minArgs arguments, got $actual")
        }
    }

    private fun seedTypeDeclFromRef(ref: ObjRef): TypeDecl? {
        val name = when (ref) {
            is LocalVarRef -> ref.name
            is LocalSlotRef -> ref.name
            is FastLocalVarRef -> ref.name
            else -> null
        } ?: return null
        seedScope?.getLocalRecordDirect(name)?.typeDecl?.let { return it }
        return seedScope?.get(name)?.typeDecl
            ?: importManager.rootScope.getLocalRecordDirect(name)?.typeDecl
            ?: importManager.rootScope.get(name)?.typeDecl
    }

    private fun lookupNamedFunctionDecl(target: ObjRef): GenericFunctionDecl? {
        val name = when (target) {
            is LocalVarRef -> target.name
            is LocalSlotRef -> target.name
            is FastLocalVarRef -> target.name
            else -> null
        } ?: return null
        return lookupGenericFunctionDecl(name)
    }

    private fun lookupNamedCallableRecord(target: ObjRef): ObjRecord? {
        val name = when (target) {
            is LocalVarRef -> target.name
            is LocalSlotRef -> target.name
            is FastLocalVarRef -> target.name
            else -> null
        } ?: return null
        findSeedScopeRecord(name)?.let { return it }
        importManager.rootScope.getLocalRecordDirect(name)?.let { return it }
        importManager.rootScope.get(name)?.let { return it }
        for (module in importedModules.asReversed()) {
            module.scope.get(name)?.let { return it }
        }
        return null
    }

    private fun inferCallReturnTypeDecl(ref: CallRef): TypeDecl? {
        callReturnTypeDeclByRef[ref]?.let { return it }
        val targetDecl = (resolveReceiverTypeDecl(ref.target) ?: seedTypeDeclFromRef(ref.target)) as? TypeDecl.Function
            ?: return null
        val bindings = mutableMapOf<String, TypeDecl>()
        val paramList = mutableListOf<TypeDecl>()
        targetDecl.receiver?.let { paramList += it }
        paramList += targetDecl.params

        fun argTypeDecl(arg: ParsedArgument): TypeDecl? {
            val stmt = arg.value as? ExpressionStatement ?: return null
            val directRef = stmt.ref
            return inferTypeDeclFromRef(directRef)
                ?: inferObjClassFromRef(directRef)?.let { TypeDecl.Simple(it.className, false) }
        }

        val ellipsisIndex = paramList.indexOfFirst { it is TypeDecl.Ellipsis }
        if (ellipsisIndex < 0) {
            val limit = minOf(paramList.size, ref.args.size)
            for (i in 0 until limit) {
                val argType = argTypeDecl(ref.args[i]) ?: continue
                collectTypeVarBindings(paramList[i], argType, bindings)
            }
        } else {
            val headCount = ellipsisIndex
            val tailCount = paramList.size - ellipsisIndex - 1
            val argCount = ref.args.size
            val headLimit = minOf(headCount, argCount)
            for (i in 0 until headLimit) {
                val argType = argTypeDecl(ref.args[i]) ?: continue
                collectTypeVarBindings(paramList[i], argType, bindings)
            }
            val tailStartArg = maxOf(headCount, argCount - tailCount)
            for (i in tailStartArg until argCount) {
                val paramIndex = paramList.size - (argCount - i)
                val argType = argTypeDecl(ref.args[i]) ?: continue
                collectTypeVarBindings(paramList[paramIndex], argType, bindings)
            }
            val ellipsisArgEnd = argCount - tailCount
            val ellipsisType = paramList[ellipsisIndex] as TypeDecl.Ellipsis
            for (i in headCount until ellipsisArgEnd) {
                val argType = if (ref.args[i].isSplat) {
                    val stmt = ref.args[i].value as? ExpressionStatement
                    stmt?.ref?.let { inferElementTypeFromSpread(it) }
                } else {
                    argTypeDecl(ref.args[i])
                } ?: continue
                collectTypeVarBindings(ellipsisType.elementType, argType, bindings)
            }
        }

        val inferred = if (bindings.isEmpty()) targetDecl.returnType
        else substituteTypeAliasTypeVars(targetDecl.returnType, bindings)
        callReturnTypeDeclByRef[ref] = inferred
        return inferred
    }

    private fun checkFunctionTypeCallTypes(
        target: ObjRef,
        args: List<ParsedArgument>,
        pos: Pos
    ) {
        lookupNamedFunctionDecl(target)?.let { decl ->
            val hasComplexArgs = args.any { it.name != null } ||
                decl.typeParams.isNotEmpty() ||
                decl.params.any { it.defaultValue != null || it.isEllipsis }
            if (hasComplexArgs) return
            val paramList = decl.params.map { if (it.isEllipsis) TypeDecl.Ellipsis(it.type) else it.type }
            if (paramList.isEmpty()) return
            val ellipsisIndex = decl.params.indexOfFirst { it.isEllipsis }
            fun argTypeDecl(arg: ParsedArgument): TypeDecl? {
                val stmt = arg.value as? ExpressionStatement ?: return null
                val ref = stmt.ref
                return inferTypeDeclFromRef(ref)
                    ?: inferObjClassFromRef(ref)?.let { TypeDecl.Simple(it.className, false) }
            }
            fun typeDeclSubtypeOf(arg: TypeDecl, param: TypeDecl): Boolean {
                if (param == TypeDecl.TypeAny || param == TypeDecl.TypeNullableAny) return true
                val (argBase, argNullable) = stripNullable(arg)
                val (paramBase, paramNullable) = stripNullable(param)
                if (argNullable && !paramNullable) return false
                if (paramBase == TypeDecl.TypeAny) return true
                if (paramBase is TypeDecl.TypeVar) return true
                if (argBase is TypeDecl.TypeVar) return true
                if (paramBase is TypeDecl.Simple && (paramBase.name == "Object" || paramBase.name == "Obj")) return true
                if (argBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase.elementType, paramBase)
                if (paramBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase, paramBase.elementType)
                return when (argBase) {
                    is TypeDecl.Union -> argBase.options.all { typeDeclSubtypeOf(it, paramBase) }
                    is TypeDecl.Intersection -> argBase.options.any { typeDeclSubtypeOf(it, paramBase) }
                    else -> when (paramBase) {
                        is TypeDecl.Union -> paramBase.options.any { typeDeclSubtypeOf(argBase, it) }
                        is TypeDecl.Intersection -> paramBase.options.all { typeDeclSubtypeOf(argBase, it) }
                        else -> {
                            val argClass = resolveTypeDeclObjClass(argBase) ?: return false
                            val paramClass = resolveTypeDeclObjClass(paramBase) ?: return false
                            argClass == paramClass || argClass.allParentsSet.contains(paramClass)
                        }
                    }
                }
            }
            fun fail(argPos: Pos, expected: TypeDecl, got: TypeDecl) {
                throw ScriptError(argPos, "argument type ${typeDeclName(got)} does not match ${typeDeclName(expected)}")
            }
            if (ellipsisIndex < 0) {
                val limit = minOf(paramList.size, args.size)
                for (i in 0 until limit) {
                    val arg = args[i]
                    val argType = argTypeDecl(arg) ?: continue
                    val paramType = paramList[i]
                    if (!typeDeclSubtypeOf(argType, paramType)) {
                        fail(arg.pos, paramType, argType)
                    }
                }
                return
            }
            val headCount = ellipsisIndex
            val tailCount = paramList.size - ellipsisIndex - 1
            val ellipsisType = paramList[ellipsisIndex] as TypeDecl.Ellipsis
            val argCount = args.size
            val headLimit = minOf(headCount, argCount)
            for (i in 0 until headLimit) {
                val arg = args[i]
                val argType = argTypeDecl(arg) ?: continue
                val paramType = paramList[i]
                if (!typeDeclSubtypeOf(argType, paramType)) {
                    fail(arg.pos, paramType, argType)
                }
            }
            val tailStartArg = maxOf(headCount, argCount - tailCount)
            for (i in tailStartArg until argCount) {
                val arg = args[i]
                val paramType = paramList[paramList.size - (argCount - i)]
                val argType = argTypeDecl(arg) ?: continue
                if (!typeDeclSubtypeOf(argType, paramType)) {
                    fail(arg.pos, paramType, argType)
                }
            }
            val ellipsisArgEnd = argCount - tailCount
            for (i in headCount until ellipsisArgEnd) {
                val arg = args[i]
                val argType = if (arg.isSplat) {
                    val stmt = arg.value as? ExpressionStatement
                    val ref = stmt?.ref
                    ref?.let { inferElementTypeFromSpread(it) }
                } else {
                    argTypeDecl(arg)
                } ?: continue
                if (!typeDeclSubtypeOf(argType, ellipsisType.elementType)) {
                    fail(arg.pos, ellipsisType.elementType, argType)
                }
            }
            return
        }
        val seededCallable = lookupNamedCallableRecord(target)
        if (seededCallable != null && seededCallable.type == ObjRecord.Type.Fun && seededCallable.value !is ObjExternCallable) {
            return
        }
        val decl = (resolveReceiverTypeDecl(target) as? TypeDecl.Function)
            ?: seedTypeDeclFromRef(target) as? TypeDecl.Function
            ?: return
        val paramList = mutableListOf<TypeDecl>()
        decl.receiver?.let { paramList += it }
        paramList += decl.params
        if (paramList.isEmpty()) return
        val ellipsisIndex = paramList.indexOfFirst { it is TypeDecl.Ellipsis }
        fun argTypeDecl(arg: ParsedArgument): TypeDecl? {
            val stmt = arg.value as? ExpressionStatement ?: return null
            val ref = stmt.ref
            return inferTypeDeclFromRef(ref)
                ?: inferObjClassFromRef(ref)?.let { TypeDecl.Simple(it.className, false) }
        }
        fun typeDeclSubtypeOf(arg: TypeDecl, param: TypeDecl): Boolean {
            if (param == TypeDecl.TypeAny || param == TypeDecl.TypeNullableAny) return true
            val (argBase, argNullable) = stripNullable(arg)
            val (paramBase, paramNullable) = stripNullable(param)
            if (argNullable && !paramNullable) return false
            if (paramBase == TypeDecl.TypeAny) return true
            if (paramBase is TypeDecl.TypeVar) return true
            if (argBase is TypeDecl.TypeVar) return true
            if (paramBase is TypeDecl.Simple && (paramBase.name == "Object" || paramBase.name == "Obj")) return true
            if (argBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase.elementType, paramBase)
            if (paramBase is TypeDecl.Ellipsis) return typeDeclSubtypeOf(argBase, paramBase.elementType)
            return when (argBase) {
                is TypeDecl.Union -> argBase.options.all { typeDeclSubtypeOf(it, paramBase) }
                is TypeDecl.Intersection -> argBase.options.any { typeDeclSubtypeOf(it, paramBase) }
                else -> when (paramBase) {
                    is TypeDecl.Union -> paramBase.options.any { typeDeclSubtypeOf(argBase, it) }
                    is TypeDecl.Intersection -> paramBase.options.all { typeDeclSubtypeOf(argBase, it) }
                    else -> {
                        val argClass = resolveTypeDeclObjClass(argBase) ?: return false
                        val paramClass = resolveTypeDeclObjClass(paramBase) ?: return false
                        argClass == paramClass || argClass.allParentsSet.contains(paramClass)
                    }
                }
            }
        }
        fun fail(argPos: Pos, expected: TypeDecl, got: TypeDecl) {
            throw ScriptError(argPos, "argument type ${typeDeclName(got)} does not match ${typeDeclName(expected)}")
        }
        if (ellipsisIndex < 0) {
            val limit = minOf(paramList.size, args.size)
            for (i in 0 until limit) {
                val arg = args[i]
                val argType = argTypeDecl(arg) ?: continue
                val paramType = paramList[i]
                if (!typeDeclSubtypeOf(argType, paramType)) {
                    fail(arg.pos, paramType, argType)
                }
            }
            return
        }
        val headCount = ellipsisIndex
        val tailCount = paramList.size - ellipsisIndex - 1
        val ellipsisType = paramList[ellipsisIndex] as TypeDecl.Ellipsis
        val argCount = args.size
        val headLimit = minOf(headCount, argCount)
        for (i in 0 until headLimit) {
            val arg = args[i]
            val argType = argTypeDecl(arg) ?: continue
            val paramType = paramList[i]
            if (!typeDeclSubtypeOf(argType, paramType)) {
                fail(arg.pos, paramType, argType)
            }
        }
        val tailStartArg = maxOf(headCount, argCount - tailCount)
        for (i in tailStartArg until argCount) {
            val arg = args[i]
            val paramType = paramList[paramList.size - (argCount - i)]
            val argType = argTypeDecl(arg) ?: continue
            if (!typeDeclSubtypeOf(argType, paramType)) {
                fail(arg.pos, paramType, argType)
            }
        }
        val ellipsisArgEnd = argCount - tailCount
        for (i in headCount until ellipsisArgEnd) {
            val arg = args[i]
            val argType = if (arg.isSplat) {
                val stmt = arg.value as? ExpressionStatement
                val ref = stmt?.ref
                ref?.let { inferElementTypeFromSpread(it) }
            } else {
                argTypeDecl(arg)
            } ?: continue
            if (!typeDeclSubtypeOf(argType, ellipsisType.elementType)) {
                fail(arg.pos, ellipsisType.elementType, argType)
            }
        }
    }

    private fun collectTypeVarBindings(
        paramType: TypeDecl,
        argType: TypeDecl,
        out: MutableMap<String, TypeDecl>
    ) {
        when (paramType) {
            is TypeDecl.TypeVar -> {
                val current = out[paramType.name]
                out[paramType.name] = mergeTypeDecls(current, argType)
            }
            is TypeDecl.Generic -> {
                if (argType is TypeDecl.Generic && argType.name == paramType.name &&
                    argType.args.size == paramType.args.size
                ) {
                    for (i in paramType.args.indices) {
                        collectTypeVarBindings(paramType.args[i], argType.args[i], out)
                    }
                }
            }
            is TypeDecl.Union -> {
                if (argType is TypeDecl.Union) {
                    val limit = minOf(paramType.options.size, argType.options.size)
                    for (i in 0 until limit) {
                        collectTypeVarBindings(paramType.options[i], argType.options[i], out)
                    }
                }
            }
            is TypeDecl.Intersection -> {
                if (argType is TypeDecl.Intersection) {
                    val limit = minOf(paramType.options.size, argType.options.size)
                    for (i in 0 until limit) {
                        collectTypeVarBindings(paramType.options[i], argType.options[i], out)
                    }
                }
            }
            else -> {}
        }
    }

    private fun mergeTypeDecls(a: TypeDecl?, b: TypeDecl): TypeDecl {
        if (a == null) return b
        if (a == TypeDecl.TypeAny || b == TypeDecl.TypeAny) {
            return if (a.isNullable || b.isNullable) TypeDecl.TypeNullableAny else TypeDecl.TypeAny
        }
        if (a == TypeDecl.TypeNullableAny || b == TypeDecl.TypeNullableAny) return TypeDecl.TypeNullableAny
        val (aBase, aNullable) = stripNullable(a)
        val (bBase, bNullable) = stripNullable(b)
        if (typeDeclKey(aBase) == typeDeclKey(bBase)) {
            return if (aNullable || bNullable) makeTypeDeclNullable(aBase) else aBase
        }
        val options = mutableListOf<TypeDecl>()
        val seen = mutableSetOf<String>()
        fun addOpt(t: TypeDecl) {
            val key = typeDeclKey(t)
            if (seen.add(key)) options += t
        }
        val nullable = aNullable || bNullable
        if (aBase is TypeDecl.Union) aBase.options.forEach { addOpt(it) } else addOpt(aBase)
        if (bBase is TypeDecl.Union) bBase.options.forEach { addOpt(it) } else addOpt(bBase)
        val merged = TypeDecl.Union(options, nullable = false)
        return if (nullable) makeTypeDeclNullable(merged) else merged
    }

    private fun typeDeclSatisfiesBound(argType: TypeDecl, bound: TypeDecl): Boolean {
        return when (argType) {
            TypeDecl.TypeAny, TypeDecl.TypeNullableAny -> true
            is TypeDecl.Union -> argType.options.all { typeDeclSatisfiesBound(it, bound) }
            is TypeDecl.Intersection -> argType.options.all { typeDeclSatisfiesBound(it, bound) }
            is TypeDecl.Ellipsis -> typeDeclSatisfiesBound(argType.elementType, bound)
            else -> when (bound) {
                is TypeDecl.Union -> bound.options.any { typeDeclSatisfiesBound(argType, it) }
                is TypeDecl.Intersection -> bound.options.all { typeDeclSatisfiesBound(argType, it) }
                is TypeDecl.Simple, is TypeDecl.Generic, is TypeDecl.Function, is TypeDecl.Ellipsis -> {
                    val argClass = resolveTypeDeclObjClass(argType) ?: return false
                    val boundClass = resolveTypeDeclObjClass(bound) ?: return false
                    argClass == boundClass || argClass.allParentsSet.contains(boundClass)
                }
                else -> true
            }
        }
    }

    private fun bindTypeParamsAtRuntime(
        context: Scope,
        argsDeclaration: ArgsDeclaration,
        typeParams: List<TypeDecl.TypeParam>
    ): Map<String, Obj> {
        if (typeParams.isEmpty()) return emptyMap()
        val explicitTypeArgs = context.args.explicitTypeArgs
        if (explicitTypeArgs.size > typeParams.size) {
            context.raiseError("too many type arguments: expected ${typeParams.size}, got ${explicitTypeArgs.size}")
        }
        val inferred = mutableMapOf<String, TypeDecl>()
        val argValues = context.args.list
        for ((index, param) in argsDeclaration.params.withIndex()) {
            val direct = argValues.getOrNull(index) ?: continue
            val value = when (direct) {
                is FrameSlotRef -> direct.read()
                is RecordSlotRef -> direct.read()
                else -> direct
            }
            collectRuntimeTypeVarBindings(param.type, value, inferred)
        }
        val boundValues = LinkedHashMap<String, Obj>(typeParams.size)
        for ((index, tp) in typeParams.withIndex()) {
            val inferredType = explicitTypeArgs.getOrNull(index)
                ?: inferred[tp.name]
                ?: tp.defaultType
                ?: TypeDecl.TypeAny
            val normalized = normalizeRuntimeTypeDecl(inferredType)
            val cls = resolveTypeDeclObjClass(normalized)
            val boundValue = if (cls != null &&
                !normalized.isNullable &&
                normalized !is TypeDecl.Union &&
                normalized !is TypeDecl.Intersection
            ) {
                cls
            } else {
                net.sergeych.lyng.obj.ObjTypeExpr(normalized)
            }
            context.addConst(tp.name, boundValue)
            boundValues[tp.name] = boundValue
            val bound = tp.bound ?: continue
            if (!typeDeclSatisfiesBound(normalized, bound)) {
                context.raiseError("type argument ${typeDeclName(normalized)} does not satisfy bound ${typeDeclName(bound)}")
            }
        }
        return boundValues
    }

    private fun collectRuntimeTypeVarBindings(
        paramType: TypeDecl,
        value: Obj,
        inferred: MutableMap<String, TypeDecl>
    ) {
        when (paramType) {
            is TypeDecl.TypeVar -> {
                if (value !== ObjNull) {
                    inferred[paramType.name] = inferRuntimeTypeDecl(value)
                }
            }
            is TypeDecl.Generic -> {
                val base = paramType.name.substringAfterLast('.')
                val arg = paramType.args.firstOrNull()
                if (base == "List" && arg is TypeDecl.TypeVar && value is ObjList) {
                    val elementType = inferListElementTypeDecl(value)
                    inferred[arg.name] = elementType
                }
            }
            else -> {}
        }
    }

    private fun inferRuntimeTypeDecl(value: Obj): TypeDecl {
        return when (value) {
            is ObjInt -> TypeDecl.Simple("Int", false)
            is ObjReal -> TypeDecl.Simple("Real", false)
            is ObjString -> TypeDecl.Simple("String", false)
            is ObjBool -> TypeDecl.Simple("Bool", false)
            is ObjChar -> TypeDecl.Simple("Char", false)
            is ObjNull -> TypeDecl.TypeNullableAny
            is ObjList -> TypeDecl.Generic("List", listOf(inferListElementTypeDecl(value)), false)
            is ObjMap -> TypeDecl.Generic("Map", listOf(inferMapKeyTypeDecl(value), inferMapValueTypeDecl(value)), false)
            is ObjClass -> TypeDecl.Simple(value.className, false)
            else -> TypeDecl.Simple(value.objClass.className, false)
        }
    }

    private fun inferListElementTypeDecl(list: ObjList): TypeDecl {
        var nullable = false
        val options = mutableListOf<TypeDecl>()
        val seen = mutableSetOf<String>()
        for (elem in list.list) {
            if (elem === ObjNull) {
                nullable = true
                continue
            }
            val elemType = inferRuntimeTypeDecl(elem)
            val base = stripNullable(elemType).first
            val key = typeDeclKey(base)
            if (seen.add(key)) options += base
        }
        val base = when {
            options.isEmpty() -> TypeDecl.TypeAny
            options.size == 1 -> options[0]
            else -> TypeDecl.Union(options, nullable = false)
        }
        return if (nullable) makeTypeDeclNullable(base) else base
    }

    private fun inferMapKeyTypeDecl(map: ObjMap): TypeDecl {
        var nullable = false
        val options = mutableListOf<TypeDecl>()
        val seen = mutableSetOf<String>()
        for (key in map.map.keys) {
            if (key === ObjNull) {
                nullable = true
                continue
            }
            val keyType = inferRuntimeTypeDecl(key)
            val base = stripNullable(keyType).first
            val k = typeDeclKey(base)
            if (seen.add(k)) options += base
        }
        val base = when {
            options.isEmpty() -> TypeDecl.TypeAny
            options.size == 1 -> options[0]
            else -> TypeDecl.Union(options, nullable = false)
        }
        return if (nullable) makeTypeDeclNullable(base) else base
    }

    private fun inferMapValueTypeDecl(map: ObjMap): TypeDecl {
        var nullable = false
        val options = mutableListOf<TypeDecl>()
        val seen = mutableSetOf<String>()
        for (value in map.map.values) {
            if (value === ObjNull) {
                nullable = true
                continue
            }
            val valueType = inferRuntimeTypeDecl(value)
            val base = stripNullable(valueType).first
            val k = typeDeclKey(base)
            if (seen.add(k)) options += base
        }
        val base = when {
            options.isEmpty() -> TypeDecl.TypeAny
            options.size == 1 -> options[0]
            else -> TypeDecl.Union(options, nullable = false)
        }
        return if (nullable) makeTypeDeclNullable(base) else base
    }

    private fun normalizeRuntimeTypeDecl(type: TypeDecl): TypeDecl {
        return when (type) {
            is TypeDecl.Union -> TypeDecl.Union(type.options.distinctBy { typeDeclKey(it) }, type.isNullable)
            is TypeDecl.Intersection -> TypeDecl.Intersection(type.options.distinctBy { typeDeclKey(it) }, type.isNullable)
            else -> type
        }
    }

    private fun resolveLocalTypeRef(name: String, pos: Pos): ObjRef? {
        val slotLoc = lookupSlotLocation(name, includeModule = true) ?: return null
        captureLocalRef(name, slotLoc, pos)?.let { return it }
        return LocalSlotRef(
            name,
            slotLoc.slot,
            slotLoc.scopeId,
            slotLoc.isMutable,
            slotLoc.isDelegated,
            pos,
            strictSlotRefs
        )
    }

    /**
     * Parse arguments list during the call and detect last block argument
     * _following the parenthesis_ call: `(1,2) { ... }`
     */
    private suspend fun parseArgs(expectedTailBlockReceiver: String? = null): Pair<List<ParsedArgument>, Boolean> {

        val args = mutableListOf<ParsedArgument>()
        suspend fun tryParseNamedArg(): ParsedArgument? {
            val save = cc.savePos()
            val t1 = cc.next()
            if (t1.type == Token.Type.ID) {
                val t2 = cc.next()
                if (t2.type == Token.Type.COLON) {
                    // name: expr
                    val name = t1.value
                    // Check for shorthand: name: (comma or rparen)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.COMMA || next.type == Token.Type.RPAREN) {
                        val localVar = resolveIdentifierRef(name, t1.pos)
                        val argPos = t1.pos
                        return ParsedArgument(ExpressionStatement(localVar, argPos), t1.pos, isSplat = false, name = name)
                    }
                    val rhs = parseExpression() ?: t2.raiseSyntax("expected expression after named argument '${name}:'")
                    return ParsedArgument(rhs, t1.pos, isSplat = false, name = name)
                }
            }
            cc.restorePos(save)
            return null
        }
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.NEWLINE,
                Token.Type.RPAREN, Token.Type.COMMA -> {
                }

                Token.Type.ELLIPSIS -> {
                    parseExpression()?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    val named = tryParseNamedArg()
                    if (named != null) {
                        args += named
                    } else {
                        parseExpression()?.let { args += ParsedArgument(it, t.pos) }
                            ?: throw ScriptError(t.pos, "Expecting arguments list")
                        // In call-site arguments, ':' is reserved for named args. Do not parse type declarations here.
                    }
                    // Here should be a valid termination:
                }
            }
        } while (t.type != Token.Type.RPAREN)
        // block after?
        val pos = cc.savePos()
        val end = cc.next()
        var lastBlockArgument = false
        if (end.type == Token.Type.LBRACE) {
            // last argument - callable
            val callableAccessor = parseLambdaExpression(expectedTailBlockReceiver)
            args += ParsedArgument(
                ExpressionStatement(callableAccessor, end.pos),
                end.pos
            )
            lastBlockArgument = true
        } else
            cc.restorePos(pos)
        return args to lastBlockArgument
    }

    /**
     * Parse arguments inside parentheses without consuming any optional trailing block after the RPAREN.
     * Useful in contexts where a following '{' has different meaning (e.g., class bodies after base lists).
     */
    private suspend fun parseArgsNoTailBlock(): List<ParsedArgument> {
        val args = mutableListOf<ParsedArgument>()
        suspend fun tryParseNamedArg(): ParsedArgument? {
            val save = cc.savePos()
            val t1 = cc.next()
                if (t1.type == Token.Type.ID) {
                val t2 = cc.next()
                if (t2.type == Token.Type.COLON) {
                    val name = t1.value
                    // Check for shorthand: name: (comma or rparen)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.COMMA || next.type == Token.Type.RPAREN) {
                        val localVar = resolveIdentifierRef(name, t1.pos)
                        val argPos = t1.pos
                        return ParsedArgument(ExpressionStatement(localVar, argPos), t1.pos, isSplat = false, name = name)
                    }
                    val rhs = parseExpression() ?: t2.raiseSyntax("expected expression after named argument '${name}:'")
                    return ParsedArgument(rhs, t1.pos, isSplat = false, name = name)
                }
            }
            cc.restorePos(save)
            return null
        }
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.NEWLINE,
                Token.Type.RPAREN, Token.Type.COMMA -> {
                }

                Token.Type.ELLIPSIS -> {
                    parseExpression()?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    val named = tryParseNamedArg()
                    if (named != null) {
                        args += named
                    } else {
                        parseExpression()?.let { args += ParsedArgument(it, t.pos) }
                            ?: throw ScriptError(t.pos, "Expecting arguments list")
                        // Do not parse type declarations in call args
                    }
                }
            }
        } while (t.type != Token.Type.RPAREN)
        // Do NOT peek for a trailing block; leave it to the outer parser
        return args
    }


    private suspend fun parseFunctionCall(
        left: ObjRef,
        blockArgument: Boolean,
        isOptional: Boolean,
        explicitTypeArgs: List<TypeDecl>? = null
    ): ObjRef {
        var detectedBlockArgument = blockArgument
        val expectedReceiver = tailBlockReceiverType(left)
        val withReceiver = when (left) {
            is LocalVarRef -> if (left.name == "with") left.name else null
            is LocalSlotRef -> if (left.name == "with") left.name else null
            else -> null
        }
        val args = if (blockArgument) {
            // Leading '{' has already been consumed by the caller token branch.
            // Parse only the lambda expression as the last argument and DO NOT
            // allow any subsequent selectors (like ".last()") to be absorbed
            // into the lambda body. This ensures expected order:
            //   foo { ... }.bar()  ==  (foo { ... }).bar()
            val callableAccessor = parseLambdaExpression(expectedReceiver)
            listOf(ParsedArgument(ExpressionStatement(callableAccessor, cc.currentPos()), cc.currentPos()))
        } else {
            if (withReceiver != null) {
                val parsedArgs = parseArgsNoTailBlock().toMutableList()
                val pos = cc.savePos()
                val end = cc.next()
                if (end.type == Token.Type.LBRACE) {
                    val receiverType = inferReceiverTypeFromArgs(parsedArgs)
                    val callableAccessor = parseLambdaExpression(receiverType, wrapAsExtensionCallable = true)
                    parsedArgs += ParsedArgument(ExpressionStatement(callableAccessor, end.pos), end.pos)
                    detectedBlockArgument = true
                } else {
                    cc.restorePos(pos)
                }
                parsedArgs
            } else {
                val r = parseArgs(expectedReceiver)
                detectedBlockArgument = r.second
                r.first
            }
        }
        val implicitThisTypeName = currentImplicitThisTypeName()
        val result = when (left) {
            is ImplicitThisMemberRef ->
                if (left.methodId == null && left.fieldId != null) {
                    CallRef(left, args, detectedBlockArgument, isOptional, explicitTypeArgs).also { callRef ->
                        applyExplicitCallTypeArgs(callRef, explicitTypeArgs)
                    }
                } else {
                    ImplicitThisMethodCallRef(
                        left.name,
                        left.methodId,
                        args,
                        detectedBlockArgument,
                        isOptional,
                        left.atPos,
                        left.preferredThisTypeName() ?: implicitThisTypeName
                    )
                }
            is LocalVarRef -> {
                val classContext = codeContexts.any { ctx -> ctx is CodeContext.ClassBody }
                val implicitThis = codeContexts.any { ctx ->
                    (ctx as? CodeContext.Function)?.implicitThisMembers == true
                }
                if ((classContext || implicitThis) && extensionNames.contains(left.name)) {
                    val receiverTypeName = implicitReceiverTypeForMember(left.name) ?: implicitThisTypeName
                    val ids = resolveImplicitThisMemberIds(left.name, left.pos(), receiverTypeName)
                    ImplicitThisMethodCallRef(
                        left.name,
                        ids.methodId,
                        args,
                        detectedBlockArgument,
                        isOptional,
                        left.pos(),
                        receiverTypeName
                    )
                } else {
                    checkFunctionTypeCallArity(left, args, left.pos())
                    checkFunctionTypeCallTypes(left, args, left.pos())
                    checkGenericBoundsAtCall(left.name, args, left.pos())
                    CallRef(left, args, detectedBlockArgument, isOptional, explicitTypeArgs).also { callRef ->
                        applyExplicitCallTypeArgs(callRef, explicitTypeArgs)
                    }
                }
            }
            is LocalSlotRef -> {
                val classContext = codeContexts.any { ctx -> ctx is CodeContext.ClassBody }
                val implicitThis = codeContexts.any { ctx ->
                    (ctx as? CodeContext.Function)?.implicitThisMembers == true
                }
                if ((classContext || implicitThis) && extensionNames.contains(left.name)) {
                    val receiverTypeName = implicitReceiverTypeForMember(left.name) ?: implicitThisTypeName
                    val ids = resolveImplicitThisMemberIds(left.name, left.pos(), receiverTypeName)
                    ImplicitThisMethodCallRef(
                        left.name,
                        ids.methodId,
                        args,
                        detectedBlockArgument,
                        isOptional,
                        left.pos(),
                        receiverTypeName
                    )
                } else {
                    checkFunctionTypeCallArity(left, args, left.pos())
                    checkFunctionTypeCallTypes(left, args, left.pos())
                    checkGenericBoundsAtCall(left.name, args, left.pos())
                    CallRef(left, args, detectedBlockArgument, isOptional, explicitTypeArgs).also { callRef ->
                        applyExplicitCallTypeArgs(callRef, explicitTypeArgs)
                    }
                }
            }
            else -> CallRef(left, args, detectedBlockArgument, isOptional, explicitTypeArgs).also { callRef ->
                applyExplicitCallTypeArgs(callRef, explicitTypeArgs)
            }
        }
        return result
    }

    private fun applyExplicitCallTypeArgs(callRef: CallRef, explicitTypeArgs: List<TypeDecl>?) {
        if (explicitTypeArgs.isNullOrEmpty()) return
        val baseName = when (val target = callRef.target) {
            is LocalVarRef -> target.name
            is FastLocalVarRef -> target.name
            is LocalSlotRef -> target.name
            is ConstRef -> (target.constValue as? ObjClass)?.className
            else -> null
        } ?: return
        callReturnTypeDeclByRef[callRef] = TypeDecl.Generic(baseName, explicitTypeArgs, isNullable = false)
    }

    private fun inferReceiverTypeFromArgs(args: List<ParsedArgument>): String? {
        val stmt = args.firstOrNull()?.value as? ExpressionStatement ?: return null
        val ref = stmt.ref
        val bySlot = (ref as? LocalSlotRef)?.let { slotRef ->
            slotTypeByScopeId[slotRef.scopeId]?.get(slotRef.slot)
        }
        val byName = (ref as? LocalVarRef)?.let { nameObjClass[it.name] }
        val cls = bySlot ?: byName ?: resolveInitializerObjClass(stmt)
        return cls?.className
    }

    private fun inferReceiverTypeFromRef(ref: ObjRef): String? {
        return when (ref) {
            is LocalSlotRef -> {
                val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
                val ownerSlot = ref.captureOwnerSlot ?: ref.slot
                slotTypeByScopeId[ownerScopeId]?.get(ownerSlot)?.className
                    ?: slotTypeDeclByScopeId[ownerScopeId]?.get(ownerSlot)?.let { typeDeclName(it) }
            }
            is LocalVarRef -> nameObjClass[ref.name]?.className
                ?: nameTypeDecl[ref.name]?.let { typeDeclName(it) }
            is FastLocalVarRef -> nameObjClass[ref.name]?.className
                ?: nameTypeDecl[ref.name]?.let { typeDeclName(it) }
            is QualifiedThisRef -> ref.typeName
            else -> resolveReceiverClassForMember(ref)?.className
        }
    }

    private suspend fun parseAccessor(): ObjRef? {
        // could be: literal
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                cc.previous()
                val n = parseNumber(true)
                ConstRef(n.asReadonly)
            }

            Token.Type.STRING -> ConstRef(ObjString(t.value).asReadonly)

            Token.Type.CHAR -> ConstRef(ObjChar(t.value[0]).asReadonly)

            Token.Type.PLUS -> {
                val n = parseNumber(true)
                ConstRef(n.asReadonly)
            }

            Token.Type.MINUS -> {
                parseNumberOrNull(false)?.let { n ->
                    ConstRef(n.asReadonly)
                } ?: run {
                    val n = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression after unary minus")
                    UnaryOpRef(UnaryOp.NEGATE, n)
                }
            }

            Token.Type.ID -> {
                // Special case: qualified this -> this@Type
                if (t.value == "this") {
                    val pos = cc.savePos()
                    val next = cc.next()
                    if (next.pos.line == t.pos.line && next.type == Token.Type.ATLABEL) {
                        resolutionSink?.reference(next.value, next.pos)
                        QualifiedThisRef(next.value, t.pos)
                    } else {
                        cc.restorePos(pos)
                        // plain this
                        resolveIdentifierRef("this", t.pos)
                    }
                } else when (t.value) {
                    "void" -> ConstRef(ObjVoid.asReadonly)
                    "null" -> ConstRef(ObjNull.asReadonly)
                    "true" -> ConstRef(ObjTrue.asReadonly)
                    "false" -> ConstRef(ObjFalse.asReadonly)
                    else -> {
                        resolveIdentifierRef(t.value, t.pos)
                    }
                }
            }

            Token.Type.OBJECT -> StatementRef(parseObjectDeclaration())

            else -> null
        }
    }

    private fun parseNumberOrNull(isPlus: Boolean): Obj? {
        val pos = cc.savePos()
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.HEX -> {
                val n = t.value.replace("_", "").toLong(if (t.type == Token.Type.HEX) 16 else 10)
                if (isPlus) ObjInt.of(n) else ObjInt.of(-n)
            }

            Token.Type.REAL -> {
                val d = t.value.toDouble()
                if (isPlus) ObjReal.of(d) else ObjReal.of(-d)
            }

            else -> {
                cc.restorePos(pos)
                null
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun parseNumber(isPlus: Boolean): Obj {
        return parseNumberOrNull(isPlus) ?: throw ScriptError(cc.currentPos(), "Expecting number")
    }

    suspend fun parseAnnotation(t: Token): (suspend (Scope, ObjString, Statement) -> Statement) {
        val extraArgs = parseArgsOrNull()
        resolutionSink?.reference(t.value, t.pos)
//        println("annotation ${t.value}: args: $extraArgs")
        return { scope, name, body ->
            val extras = extraArgs?.first?.toArguments(scope, extraArgs.second)?.list
            val required = listOf(name, body)
            val args = extras?.let { required + it } ?: required
            val fn = scope.get(t.value)?.value ?: scope.raiseSymbolNotFound("annotation not found: ${t.value}")
            if (fn !is Statement) scope.raiseIllegalArgument("annotation must be callable, got ${fn.objClass}")
            (fn.execute(scope.createChildScope(Arguments(args))) as? Statement)
                ?: scope.raiseClassCastError("function annotation must return callable")
        }
    }

    suspend fun parseArgsOrNull(): Pair<List<ParsedArgument>, Boolean>? =
        if (cc.skipNextIf(Token.Type.LPAREN))
            parseArgs()
        else
            null

    private suspend fun parseDeclarationWithModifiers(firstId: Token): Statement {
        val modifiers = mutableSetOf<String>()
        var currentToken = firstId

        while (true) {
            when (currentToken.value) {
                "private", "protected", "static", "abstract", "closed", "override", "extern", "open" -> {
                    modifiers.add(currentToken.value)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.ID || next.type == Token.Type.OBJECT) {
                        currentToken = nextNonWhitespace()
                    } else {
                        break
                    }
                }

                else -> break
            }
        }

        val visibility = when {
            modifiers.contains("private") -> Visibility.Private
            modifiers.contains("protected") -> Visibility.Protected
            else -> Visibility.Public
        }
        val isStatic = modifiers.contains("static")
        val isAbstract = modifiers.contains("abstract")
        val isClosed = modifiers.contains("closed")
        val isOverride = modifiers.contains("override")
        val isExtern = modifiers.contains("extern")

        if (isStatic && (isAbstract || isOverride || isClosed))
            throw ScriptError(currentToken.pos, "static members cannot be abstract, closed or override")

        if (visibility == Visibility.Private && isAbstract)
            throw ScriptError(currentToken.pos, "abstract members cannot be private")

        pendingDeclStart = firstId.pos
        // pendingDeclDoc might be already set by an annotation
        if (pendingDeclDoc == null)
            pendingDeclDoc = consumePendingDoc()

        val isMember = (codeContexts.lastOrNull() is CodeContext.ClassBody)

        if (!isMember && isClosed && currentToken.value != "class")
            throw ScriptError(currentToken.pos, "modifier closed at top level is only allowed for classes")

        if (!isMember && isOverride && currentToken.value != "fun" && currentToken.value != "fn")
            throw ScriptError(currentToken.pos, "modifier override outside class is only allowed for extension functions")

        if (!isMember && isAbstract && currentToken.value != "class")
            throw ScriptError(currentToken.pos, "modifier abstract at top level is only allowed for classes")

        return when (currentToken.value) {
            "val" -> parseVarDeclaration(false, visibility, isAbstract, isClosed, isOverride, isStatic, isExtern)
            "var" -> parseVarDeclaration(true, visibility, isAbstract, isClosed, isOverride, isStatic, isExtern)
            "fun", "fn" -> parseFunctionDeclaration(visibility, isAbstract, isClosed, isOverride, isExtern, isStatic)
            "class" -> {
                if (isStatic || isOverride)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for class: ${modifiers.joinToString(" ")}"
                    )
                parseClassDeclaration(isAbstract, isExtern, isClosed)
            }

            "object" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for object: ${modifiers.joinToString(" ")}"
                    )
                parseObjectDeclaration(isExtern)
            }

            "interface" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for interface: ${modifiers.joinToString(" ")}"
                    )
                // interface is synonym for abstract class
                parseClassDeclaration(isAbstract = true, isExtern = isExtern)
            }

            "enum" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for enum: ${modifiers.joinToString(" ")}"
                    )
                parseEnumDeclaration(isExtern)
            }

            else -> throw ScriptError(
                currentToken.pos,
                "expected declaration after modifiers, found ${currentToken.value}"
            )
        }
    }

    /**
     * Parse keyword-starting statement.
     * @return parsed statement or null if, for example. [id] is not among keywords
     */
    private suspend fun parseKeywordStatement(id: Token): Statement? = when (id.value) {
        "abstract", "closed", "override", "extern", "private", "protected", "static", "open" -> {
            parseDeclarationWithModifiers(id)
        }

        "interface" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseClassDeclaration(isAbstract = true)
        }

        "val" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseVarDeclaration(false, Visibility.Public)
        }

        "var" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseVarDeclaration(true, Visibility.Public)
        }
        // Ensure function declarations are recognized in all contexts (including class bodies)
        "fun" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseFunctionDeclaration(isExtern = false, isStatic = false)
        }

        "fn" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseFunctionDeclaration(isExtern = false, isStatic = false)
        }
        // Visibility modifiers for declarations: private/protected val/var/fun/fn
        "while" -> parseWhileStatement()
        "do" -> parseDoWhileStatement()
        "for" -> parseForStatement()
        "return" -> parseReturnStatement(id.pos)
        "break" -> parseBreakStatement(id.pos)
        "continue" -> parseContinueStatement(id.pos)
        "if" -> parseIfStatement()
        "class" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseClassDeclaration()
        }

        "object" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseObjectDeclaration()
        }

        "init" -> {
            if (codeContexts.lastOrNull() is CodeContext.ClassBody && cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
                val implicitThisType = classCtx?.name
                miniSink?.onEnterFunction(null)
                val block = inCodeContext(
                    CodeContext.Function(
                        "<init>",
                        implicitThisMembers = true,
                        implicitThisTypeName = implicitThisType
                    )
                ) {
                    parseBlock()
                }
                miniSink?.onExitFunction(cc.currentPos())
                lastParsedBlockRange?.let { range ->
                    miniSink?.onInitDecl(MiniInitDecl(MiniRange(id.pos, range.end), id.pos))
                }
                val initStmt = wrapInstanceInitBytecode(block, "init@${id.pos}")
                ClassInstanceInitDeclStatement(initStmt, id.pos)
            } else null
        }

        "enum" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseEnumDeclaration()
        }

        "type" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            if (looksLikeTypeAliasDeclaration()) {
                parseTypeAliasDeclaration()
            } else {
                null
            }
        }

        "try" -> parseTryStatement()
        "throw" -> parseThrowStatement(id.pos)
        "when" -> parseWhenStatement()
        else -> {
            // triples
            cc.previous()
            val isExtern = cc.skipId("extern")
            when {
                cc.matchQualifiers("fun", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc()
                    parseFunctionDeclaration(Visibility.Private, isExtern)
                }

                cc.matchQualifiers("fun", "private", "static") -> parseFunctionDeclaration(
                    Visibility.Private,
                    isExtern,
                    isStatic = true
                )

                cc.matchQualifiers("fun", "static") -> parseFunctionDeclaration(
                    Visibility.Public,
                    isExtern,
                    isStatic = true
                )

                cc.matchQualifiers("fn", "private") -> parseFunctionDeclaration(Visibility.Private, isExtern)
                cc.matchQualifiers("fun", "open") -> parseFunctionDeclaration(isExtern = isExtern)
                cc.matchQualifiers("fn", "open") -> parseFunctionDeclaration(isExtern = isExtern)

                cc.matchQualifiers("fun") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc =
                        consumePendingDoc(); parseFunctionDeclaration(isExtern = isExtern)
                }

                cc.matchQualifiers("fn") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc =
                        consumePendingDoc(); parseFunctionDeclaration(isExtern = isExtern)
                }

                cc.matchQualifiers("val", "private", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("val", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Public,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("val", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private
                    )
                }

                cc.matchQualifiers("var", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Public,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("var", "static", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("var", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private
                    )
                }

                cc.matchQualifiers("val", "open") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private,
                        true
                    )
                }

                cc.matchQualifiers("var", "open") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private,
                        true
                    )
                }

                else -> {
                    cc.next()
                    null
                }
            }
        }
    }

    private suspend fun parseWhenStatement(): Statement {
        // has a value, when(value) ?
        var t = cc.nextNonWhitespace()
        val stmt = if (t.type == Token.Type.LPAREN) {
            // when(value)
            val value = parseStatement() ?: throw ScriptError(cc.currentPos(), "when(value) expected")
            cc.skipTokenOfType(Token.Type.RPAREN)
            t = cc.next()
            if (t.type != Token.Type.LBRACE) throw ScriptError(t.pos, "when { ... } expected")
            val cases = mutableListOf<WhenCase>()
            var elseCase: Statement? = null

            // there could be 0+ then clauses
            // condition could be a value, in and is clauses:
            // parse several conditions for one then clause


            // loop cases
            outer@ while (true) {
                var skipParseBody = false
                val currentConditions = mutableListOf<WhenCondition>()

                // loop conditions
                while (true) {
                    t = cc.nextNonWhitespace()

                    when (t.type) {
                        Token.Type.IN,
                        Token.Type.NOTIN -> {
                            val negated = t.type == Token.Type.NOTIN
                            val container = parseExpression() ?: throw ScriptError(cc.currentPos(), "type expected")
                            currentConditions += WhenInCondition(container, negated, t.pos)
                        }

                        Token.Type.IS,
                        Token.Type.NOTIS -> {
                            val negated = t.type == Token.Type.NOTIS
                            if (tryConsumeNullablePredicate()) {
                                currentConditions += WhenNullableCondition(negated, t.pos)
                            } else {
                                val (typeDecl, _) = parseTypeExpressionWithMini()
                                val typeRef = net.sergeych.lyng.obj.TypeDeclRef(typeDecl, t.pos)
                                val caseType = ExpressionStatement(typeRef, t.pos)
                                currentConditions += WhenIsCondition(caseType, negated, t.pos)
                            }
                        }

                        Token.Type.COMMA ->
                            continue

                        Token.Type.ARROW ->
                            break

                        Token.Type.RBRACE ->
                            break@outer

                        else -> {
                            if (t.type == Token.Type.ID && t.value == "nullable") {
                                currentConditions += WhenNullableCondition(negated = false, t.pos)
                            } else if (t.value == "else") {
                                cc.skipTokens(Token.Type.ARROW)
                                if (elseCase != null) throw ScriptError(
                                    cc.currentPos(),
                                    "when else block already defined"
                                )
                                elseCase = parseStatement()?.let { unwrapBytecodeDeep(it) }
                                    ?: throw ScriptError(
                                        cc.currentPos(),
                                        "when else block expected"
                                    )
                                skipParseBody = true
                            } else {
                                cc.previous()
                                val x = parseExpression()
                                    ?: throw ScriptError(cc.currentPos(), "when case condition expected")
                                currentConditions += WhenEqualsCondition(x, t.pos)
                            }
                        }
                    }
                }
                // parsed conditions?
                if (!skipParseBody) {
                    val block = parseStatement()?.let { unwrapBytecodeDeep(it) }
                        ?: throw ScriptError(cc.currentPos(), "when case block expected")
                    cases += WhenCase(currentConditions, block)
                }
            }
            val whenPos = t.pos
            WhenStatement(value, cases, elseCase, whenPos)
        } else {
            // when { cond -> ... }
            throw ScriptError(t.pos, "when without subject is not implemented")
        }
        return wrapBytecode(stmt)
    }

    private suspend fun parseThrowStatement(start: Pos): Statement {
        val throwStatement = parseStatement() ?: throw ScriptError(cc.currentPos(), "throw object expected")
        return wrapBytecode(ThrowStatement(throwStatement, start))
    }

    private data class CatchBlockData(
        val catchVar: Token,
        val classNames: List<String>,
        val block: Statement
    )

    private suspend fun parseTryStatement(): Statement {
        fun withCatchSlot(block: Statement, catchName: String): Statement {
            val stmt = block as? BlockStatement ?: return block
            if (stmt.slotPlan.containsKey(catchName)) return stmt
            val basePlan = stmt.slotPlan
            val newPlan = LinkedHashMap<String, Int>(basePlan.size + 1)
            newPlan[catchName] = 0
            for ((name, idx) in basePlan) {
                newPlan[name] = idx + 1
            }
            return BlockStatement(stmt.block, newPlan, stmt.scopeId, stmt.captureSlots, stmt.pos)
        }
        fun stripCatchCaptures(block: Statement): Statement {
            val stmt = block as? BlockStatement ?: return block
            if (stmt.captureSlots.isEmpty()) return stmt
            return BlockStatement(stmt.block, stmt.slotPlan, stmt.scopeId, emptyList(), stmt.pos)
        }
        fun resolveCatchVarClass(names: List<String>): ObjClass? {
            if (names.size == 1) {
                val name = names.first()
                return resolveClassByName(name) ?: if (name == "Exception") ObjException.Root else null
            }
            return ObjException.Root
        }

        val body = unwrapBytecodeDeep(parseBlock())
        val catches = mutableListOf<CatchBlockData>()
        cc.skipTokens(Token.Type.NEWLINE)
        var t = cc.next()
        while (t.value == "catch") {

            if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                t = cc.next()
                if (t.type != Token.Type.ID) throw ScriptError(t.pos, "expected catch variable")
                val catchVar = t

                val exClassNames = mutableListOf<String>()
                if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
                    // load list of exception classes
                    do {
                        t = cc.next()
                        if (t.type != Token.Type.ID)
                            throw ScriptError(t.pos, "expected exception class name")
                        exClassNames += t.value
                        resolutionSink?.reference(t.value, t.pos)
                        t = cc.next()
                        when (t.type) {
                            Token.Type.COMMA -> {
                                continue
                            }

                            Token.Type.RPAREN -> {
                                break
                            }

                            else -> throw ScriptError(t.pos, "syntax error: expected ',' or ')'")
                        }
                    } while (true)
                } else {
                    // no type!
                    exClassNames += "Exception"
                    cc.skipTokenOfType(Token.Type.RPAREN)
                }
                val block = try {
                    resolutionSink?.enterScope(ScopeKind.BLOCK, catchVar.pos, null)
                    resolutionSink?.declareSymbol(catchVar.value, SymbolKind.LOCAL, isMutable = false, pos = catchVar.pos)
                    val catchType = resolveCatchVarClass(exClassNames)
                    stripCatchCaptures(
                        withCatchSlot(
                            unwrapBytecodeDeep(
                                parseBlockWithPredeclared(
                                    listOf(catchVar.value to false),
                                    predeclaredTypes = catchType?.let { mapOf(catchVar.value to it) } ?: emptyMap()
                                )
                            ),
                            catchVar.value
                        )
                    )
                } finally {
                    resolutionSink?.exitScope(cc.currentPos())
                }
                catches += CatchBlockData(catchVar, exClassNames, block)
                cc.skipTokens(Token.Type.NEWLINE)
                t = cc.next()
            } else {
                // no (e: Exception) block: should be the shortest variant `catch { ... }`
                cc.skipTokenOfType(Token.Type.LBRACE, "expected catch(...) or catch { ... } here")
                val itToken = Token("it", cc.currentPos(), Token.Type.ID)
                val block = try {
                    resolutionSink?.enterScope(ScopeKind.BLOCK, itToken.pos, null)
                    resolutionSink?.declareSymbol(itToken.value, SymbolKind.LOCAL, isMutable = false, pos = itToken.pos)
                    val catchType = resolveCatchVarClass(listOf("Exception"))
                    stripCatchCaptures(
                        withCatchSlot(
                            unwrapBytecodeDeep(
                                parseBlockWithPredeclared(
                                    listOf(itToken.value to false),
                                    skipLeadingBrace = true,
                                    predeclaredTypes = catchType?.let { mapOf(itToken.value to it) } ?: emptyMap()
                                )
                            ),
                            itToken.value
                        )
                    )
                } finally {
                    resolutionSink?.exitScope(cc.currentPos())
                }
                catches += CatchBlockData(itToken, listOf("Exception"), block)
                t = cc.next()
            }
        }
        val finallyClause = if (t.value == "finally") {
            unwrapBytecodeDeep(parseBlock())
        } else {
            cc.previous()
            null
        }

        if (catches.isEmpty() && finallyClause == null)
            throw ScriptError(cc.currentPos(), "try block must have either catch or finally clause or both")

        val stmtPos = body.pos
        val tryCatches = catches.map { cdata ->
            TryStatement.CatchBlock(
                catchVarName = cdata.catchVar.value,
                catchVarPos = cdata.catchVar.pos,
                classNames = cdata.classNames,
                block = cdata.block
            )
        }
        return TryStatement(body, tryCatches, finallyClause, stmtPos)
    }

    private fun parseEnumDeclaration(isExtern: Boolean = false): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        val startPos = pendingDeclStart ?: nameToken.pos
        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null
        val declaredName = nameToken.value
        val outerClassName = currentEnclosingClassName()
        val qualifiedName = if (outerClassName != null) "$outerClassName.$declaredName" else declaredName
        resolutionSink?.declareSymbol(declaredName, SymbolKind.ENUM, isMutable = false, pos = nameToken.pos)
        if (codeContexts.lastOrNull() is CodeContext.Module) {
            scopeSeedNames.add(declaredName)
        }
        if (outerClassName != null) {
            val outerCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            outerCtx?.classScopeMembers?.add(declaredName)
            registerClassScopeMember(outerClassName, declaredName)
            registerClassScopeCallableMember(outerClassName, declaredName)
        }
        val lifted = if (cc.peekNextNonWhitespace().type == Token.Type.STAR) {
            cc.nextNonWhitespace()
            true
        } else {
            false
        }
        // so far only simplest enums:
        val names = mutableListOf<String>()
        val positions = mutableListOf<Pos>()
        // skip '{'
        cc.skipTokenOfType(Token.Type.LBRACE)

        if (cc.peekNextNonWhitespace().type != Token.Type.RBRACE) {
            do {
                val t = cc.nextNonWhitespace()
                when (t.type) {
                    Token.Type.ID -> {
                        names += t.value
                        positions += t.pos
                        val t1 = cc.nextNonWhitespace()
                        when (t1.type) {
                            Token.Type.COMMA ->
                                continue

                            Token.Type.RBRACE -> break
                            else -> {
                                t1.raiseSyntax("unexpected token")
                            }
                        }
                    }

                    else -> t.raiseSyntax("expected enum entry name")
                }
            } while (true)
        } else {
            cc.nextNonWhitespace()
        }

        miniSink?.onEnumDecl(
            MiniEnumDecl(
                range = MiniRange(startPos, cc.currentPos()),
                name = declaredName,
                entries = names,
                doc = doc,
                nameStart = nameToken.pos,
                isExtern = isExtern,
                entryPositions = positions
            )
        )
        if (lifted) {
            val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            val conflicts = when {
                classCtx != null -> names.filter { entry ->
                    classCtx.classScopeMembers.contains(entry) || classCtx.declaredMembers.contains(entry)
                }
                else -> {
                    val modulePlan = moduleSlotPlan()
                    names.filter { entry -> modulePlan?.slots?.containsKey(entry) == true }
                }
            }
            if (conflicts.isNotEmpty()) {
                val entry = conflicts.first()
                val disambiguation = "${qualifiedName}.$entry"
                throw ScriptError(
                    nameToken.pos,
                    "lifted enum entry '$entry' conflicts with existing member; use '$disambiguation'"
                )
            }
        }
        val fieldIds = LinkedHashMap<String, Int>(names.size + 1)
        fieldIds["entries"] = 0
        for ((index, entry) in names.withIndex()) {
            fieldIds[entry] = index + 1
        }
        val baseMethodMax = net.sergeych.lyng.obj.EnumBase
            .instanceMethodIdMap(includeAbstract = true)
            .values
            .maxOrNull() ?: -1
        val methodIds = linkedMapOf(
            "valueOf" to baseMethodMax + 1,
            "name" to baseMethodMax + 2,
            "ordinal" to baseMethodMax + 3
        )
        compileClassInfos[qualifiedName] = CompileClassInfo(
            name = qualifiedName,
            packageName = packageName,
            fieldIds = fieldIds,
            methodIds = methodIds,
            nextFieldId = fieldIds.size,
            nextMethodId = methodIds.size,
            baseNames = listOf("Enum")
        )
        enumEntriesByName[qualifiedName] = names.toList()
        registerClassScopeFieldType(outerClassName, declaredName, qualifiedName)
        if (lifted && outerClassName != null) {
            val outerCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            for (entry in names) {
                outerCtx?.classScopeMembers?.add(entry)
                registerClassScopeMember(outerClassName, entry)
                registerClassScopeFieldType(outerClassName, entry, qualifiedName)
            }
        } else if (lifted) {
            for (entry in names) {
                declareLocalName(entry, isMutable = false)
            }
        }

        val stmtPos = startPos
        return EnumDeclStatement(
            declaredName = declaredName,
            qualifiedName = qualifiedName,
            entries = names.toList(),
            lifted = lifted,
            startPos = stmtPos
        )
    }

    private suspend fun parseObjectDeclaration(isExtern: Boolean = false): Statement {
        val next = cc.peekNextNonWhitespace()
        val nameToken = if (next.type == Token.Type.ID) cc.requireToken(Token.Type.ID) else null

        val startPos = pendingDeclStart ?: nameToken?.pos ?: cc.current().pos
        val declaredName = nameToken?.value
        val outerClassName = currentEnclosingClassName()
        val baseName = declaredName ?: generateAnonName(startPos)
        val className = if (declaredName != null && outerClassName != null) "$outerClassName.$declaredName" else baseName
        if (declaredName != null) {
            val namePos = nameToken.pos
            resolutionSink?.declareSymbol(declaredName, SymbolKind.CLASS, isMutable = false, pos = namePos)
            declareLocalName(declaredName, isMutable = false)
            if (codeContexts.lastOrNull() is CodeContext.Module) {
                objectDeclNames.add(declaredName)
            }
            if (outerClassName != null) {
                val outerCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
                outerCtx?.classScopeMembers?.add(declaredName)
                registerClassScopeMember(outerClassName, declaredName)
            }
        }

        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null

        // Optional base list: ":" Base ("," Base)* where Base := ID ( "(" args? ")" )?
        data class BaseSpec(val name: String, val args: List<ParsedArgument>?)

        val baseSpecs = mutableListOf<BaseSpec>()
        if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
            do {
                val baseId = cc.requireToken(Token.Type.ID, "base class name expected")
                resolutionSink?.reference(baseId.value, baseId.pos)
                var argsList: List<ParsedArgument>? = null
                if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                    argsList = parseArgsNoTailBlock()
                }
                argsList = wrapParsedArgsBytecode(argsList)
                baseSpecs += BaseSpec(baseId.value, argsList)
            } while (cc.skipTokenOfType(Token.Type.COMMA, isOptional = true))
        }

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)

        pushInitScope()

        // Robust body detection
        var classBodyRange: MiniRange? = null
        val bodyInit: Statement? = inCodeContext(CodeContext.ClassBody(className, isExtern = isExtern)) {
            val saved = cc.savePos()
            val nextBody = cc.nextNonWhitespace()
            if (nextBody.type == Token.Type.LBRACE) {
                // Emit MiniClassDecl before body parsing to track members via enter/exit
                run {
                    val node = MiniClassDecl(
                        range = MiniRange(startPos, cc.currentPos()),
                        name = declaredName ?: className,
                        bases = baseSpecs.map { it.name },
                        bodyRange = null,
                        doc = doc,
                        nameStart = nameToken?.pos ?: startPos,
                        isObject = true,
                        isExtern = isExtern
                    )
                    miniSink?.onEnterClass(node)
                }
                val bodyStart = nextBody.pos
                val classSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
                slotPlanStack.add(classSlotPlan)
                resolutionSink?.declareClass(className, baseSpecs.map { it.name }, startPos)
                resolutionSink?.enterScope(ScopeKind.CLASS, startPos, className, baseSpecs.map { it.name })
                val classCtx = codeContexts.lastOrNull() as? CodeContext.ClassBody
                classCtx?.let { ctx ->
                    val callableMembers = classScopeCallableMembersByClassName.getOrPut(className) { mutableSetOf() }
                    predeclareClassScopeMembers(className, ctx.classScopeMembers, callableMembers)
                    val baseIds = collectBaseMemberIds(baseSpecs.map { it.name })
                    ctx.memberFieldIds.putAll(baseIds.fieldIds)
                    ctx.memberMethodIds.putAll(baseIds.methodIds)
                    ctx.nextFieldId = maxOf(ctx.nextFieldId, baseIds.nextFieldId)
                    ctx.nextMethodId = maxOf(ctx.nextMethodId, baseIds.nextMethodId)
                }
                val st = try {
                    val parsed = withLocalNames(emptySet()) {
                        parseScript()
                    }
                    if (!isExtern) {
                        classCtx?.let { ctx ->
                            compileClassInfos[className] = CompileClassInfo(
                                name = className,
                                packageName = packageName,
                                fieldIds = ctx.memberFieldIds.toMap(),
                                methodIds = ctx.memberMethodIds.toMap(),
                                nextFieldId = ctx.nextFieldId,
                                nextMethodId = ctx.nextMethodId,
                                baseNames = baseSpecs.map { it.name }
                            )
                        }
                    }
                    if (declaredName != null) {
                        registerClassScopeFieldType(outerClassName, declaredName, className)
                    }
                    parsed
                } finally {
                    slotPlanStack.removeLast()
                    resolutionSink?.exitScope(cc.currentPos())
                }
                val rbTok = cc.next()
                if (rbTok.type != Token.Type.RBRACE) throw ScriptError(rbTok.pos, "unbalanced braces in object body")
                classBodyRange = MiniRange(bodyStart, rbTok.pos)
                miniSink?.onExitClass(rbTok.pos)
                st
            } else {
                // No body, but still emit the class
                run {
                    val node = MiniClassDecl(
                        range = MiniRange(startPos, cc.currentPos()),
                        name = declaredName ?: className,
                        bases = baseSpecs.map { it.name },
                        bodyRange = null,
                        doc = doc,
                        nameStart = nameToken?.pos ?: startPos,
                        isObject = true,
                        isExtern = isExtern
                    )
                    miniSink?.onClassDecl(node)
                }
                resolutionSink?.declareClass(className, baseSpecs.map { it.name }, startPos)
                if (!isExtern) {
                    val baseIds = collectBaseMemberIds(baseSpecs.map { it.name })
                    compileClassInfos[className] = CompileClassInfo(
                        name = className,
                        packageName = packageName,
                        fieldIds = baseIds.fieldIds,
                        methodIds = baseIds.methodIds,
                        nextFieldId = baseIds.nextFieldId,
                        nextMethodId = baseIds.nextMethodId,
                        baseNames = baseSpecs.map { it.name }
                    )
                }
                if (declaredName != null) {
                    registerClassScopeFieldType(outerClassName, declaredName, className)
                }
                cc.restorePos(saved)
                null
            }
        }

        val initScope = popInitScope()
        val wrappedBodyInit = bodyInit?.let { wrapClassBodyBytecode(it, "object@$className") }

        val spec = ClassDeclSpec(
            declaredName = declaredName,
            className = className,
            typeName = className,
            startPos = startPos,
            isExtern = false,
            isAbstract = false,
            isClosed = false,
            isObject = true,
            isAnonymous = nameToken == null,
            baseSpecs = baseSpecs.map { ClassDeclBaseSpec(it.name, it.args) },
            constructorArgs = null,
            constructorFieldIds = null,
            bodyInit = wrappedBodyInit,
            initScope = emptyList()
        )
        return ClassDeclStatement(spec)
    }

    private suspend fun parseClassDeclaration(isAbstract: Boolean = false, isExtern: Boolean = false, isClosed: Boolean = false): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        val startPos = pendingDeclStart ?: nameToken.pos
        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null
        val declaredName = nameToken.value
        val outerClassName = currentEnclosingClassName()
        val qualifiedName = if (outerClassName != null) "$outerClassName.$declaredName" else declaredName
        resolutionSink?.declareSymbol(declaredName, SymbolKind.CLASS, isMutable = false, pos = nameToken.pos)
        if (codeContexts.lastOrNull() is CodeContext.Module) {
            declareLocalName(declaredName, isMutable = false)
        }
        if (codeContexts.lastOrNull() is CodeContext.Module) {
            scopeSeedNames.add(declaredName)
        }
        if (outerClassName != null) {
            val outerCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            outerCtx?.classScopeMembers?.add(declaredName)
            registerClassScopeMember(outerClassName, declaredName)
            registerClassScopeCallableMember(outerClassName, declaredName)
        }
        return inCodeContext(CodeContext.ClassBody(qualifiedName, isExtern = isExtern)) {
            val classCtx = codeContexts.lastOrNull() as? CodeContext.ClassBody
            val typeParamDecls = parseTypeParamList()
            classCtx?.typeParamDecls = typeParamDecls
            val classTypeParams = typeParamDecls.map { it.name }.toSet()
            classCtx?.typeParams = classTypeParams
            pendingTypeParamStack.add(classTypeParams)
            var constructorArgsDeclaration: ArgsDeclaration?
            try {
                constructorArgsDeclaration =
                    if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true))
                        parseArgsDeclaration(isClassDeclaration = true)
                    else ArgsDeclaration(emptyList(), Token.Type.RPAREN)
            } finally {
                pendingTypeParamStack.removeLast()
            }

            if (constructorArgsDeclaration != null && constructorArgsDeclaration.endTokenType != Token.Type.RPAREN)
                throw ScriptError(
                    nameToken.pos,
                    "Bad class declaration: expected ')' at the end of the primary constructor"
                )

            constructorArgsDeclaration?.params?.forEach { param ->
                if (param.accessType != null) {
                    val declClass = resolveTypeDeclObjClass(param.type)
                    if (declClass != null) {
                        classFieldTypesByName.getOrPut(qualifiedName) { mutableMapOf() }[param.name] = declClass
                    }
                }
            }

            val classSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
            classCtx?.slotPlanId = classSlotPlan.id
            constructorArgsDeclaration?.params?.forEach { param ->
                val mutable = param.accessType?.isMutable ?: false
                declareSlotNameIn(classSlotPlan, param.name, mutable, isDelegated = false)
            }
            val ctorForcedLocalSlots = LinkedHashMap<String, Int>()
            if (constructorArgsDeclaration != null) {
                val ctorDecl = constructorArgsDeclaration
                val snapshot = slotPlanIndices(classSlotPlan)
                for (param in ctorDecl.params) {
                    val idx = snapshot[param.name] ?: continue
                    ctorForcedLocalSlots[param.name] = idx
                }
                constructorArgsDeclaration =
                    wrapDefaultArgsBytecode(ctorDecl, ctorForcedLocalSlots, classSlotPlan.id)
            }
            constructorArgsDeclaration?.params?.forEach { param ->
                if (param.accessType != null) {
                    classCtx?.declaredMembers?.add(param.name)
                }
            }

            // Optional base list: ":" Base ("," Base)* where Base := ID ( "(" args? ")" )?
            data class BaseSpec(val name: String, val args: List<ParsedArgument>?)

            val baseSpecs = mutableListOf<BaseSpec>()
            pendingTypeParamStack.add(classTypeParams)
            slotPlanStack.add(classSlotPlan)
            try {
                if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
                    do {
                        val (baseDecl, _) = parseSimpleTypeExpressionWithMini()
                        val baseName = when (baseDecl) {
                            is TypeDecl.Simple -> baseDecl.name
                            is TypeDecl.Generic -> baseDecl.name
                            else -> throw ScriptError(cc.currentPos(), "base class name expected")
                        }
                        var argsList: List<ParsedArgument>? = null
                        // Optional constructor args of the base — parse and ignore for now (MVP), just to consume tokens
                        if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                            // Parse args without consuming any following block so that a class body can follow safely
                            argsList = parseArgsNoTailBlock()
                        }
                        argsList = wrapParsedArgsBytecode(argsList, ctorForcedLocalSlots, classSlotPlan.id)
                        baseSpecs += BaseSpec(baseName, argsList)
                    } while (cc.skipTokenOfType(Token.Type.COMMA, isOptional = true))
                }
            } finally {
                slotPlanStack.removeLast()
                pendingTypeParamStack.removeLast()
            }

            cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)

            pushInitScope()
            // Robust body detection: peek next non-whitespace token; if it's '{', consume and parse the body
            var classBodyRange: MiniRange? = null
            val bodyInit: Statement? = run {
                val saved = cc.savePos()
                val next = cc.nextNonWhitespace()
                
                val ctorFields = mutableListOf<MiniCtorField>()
                constructorArgsDeclaration?.let { ad ->
                    for (p in ad.params) {
                        val at = p.accessType
                        val mutable = at == AccessType.Var
                        ctorFields += MiniCtorField(
                            name = p.name,
                            mutable = mutable,
                            type = p.miniType,
                            nameStart = p.pos
                        )
                    }
                }

                if (next.type == Token.Type.LBRACE) {
                    // Emit MiniClassDecl before body parsing to track members via enter/exit
                    run {
                        val node = MiniClassDecl(
                            range = MiniRange(startPos, cc.currentPos()),
                            name = declaredName,
                            bases = baseSpecs.map { it.name },
                            bodyRange = null,
                            ctorFields = ctorFields,
                            doc = doc,
                            nameStart = nameToken.pos,
                            isExtern = isExtern
                        )
                        miniSink?.onEnterClass(node)
                    }
                    // parse body
                    val bodyStart = next.pos
                    slotPlanStack.add(classSlotPlan)
                    resolutionSink?.declareClass(qualifiedName, baseSpecs.map { it.name }, startPos)
                    resolutionSink?.enterScope(ScopeKind.CLASS, startPos, qualifiedName, baseSpecs.map { it.name })
                    constructorArgsDeclaration?.params?.forEach { param ->
                        val accessType = param.accessType
                        val kind = if (accessType != null) SymbolKind.MEMBER else SymbolKind.PARAM
                        val mutable = accessType?.isMutable ?: false
                        resolutionSink?.declareSymbol(param.name, kind, mutable, param.pos)
                    }
                    val st = try {
                        classCtx?.let { ctx ->
                            val callableMembers = classScopeCallableMembersByClassName.getOrPut(qualifiedName) { mutableSetOf() }
                            predeclareClassScopeMembers(qualifiedName, ctx.classScopeMembers, callableMembers)
                            predeclareClassMembers(ctx.declaredMembers, ctx.memberOverrides)
                            val existingExternInfo = if (isExtern) resolveCompileClassInfo(qualifiedName) else null
                            if (existingExternInfo != null) {
                                ctx.memberFieldIds.putAll(existingExternInfo.fieldIds)
                                ctx.memberMethodIds.putAll(existingExternInfo.methodIds)
                                ctx.nextFieldId = maxOf(ctx.nextFieldId, existingExternInfo.nextFieldId)
                                ctx.nextMethodId = maxOf(ctx.nextMethodId, existingExternInfo.nextMethodId)
                                for (member in ctx.declaredMembers) {
                                    val hasField = member in existingExternInfo.fieldIds
                                    val hasMethod = member in existingExternInfo.methodIds
                                    if (!hasField && !hasMethod) {
                                        throw ScriptError(nameToken.pos, "extern member $member is not found in runtime class $qualifiedName")
                                    }
                                }
                                constructorArgsDeclaration?.params?.forEach { param ->
                                    if (param.accessType == null) return@forEach
                                    if (!ctx.memberFieldIds.containsKey(param.name)) {
                                        val fieldId = existingExternInfo.fieldIds[param.name]
                                            ?: throw ScriptError(nameToken.pos, "extern field ${param.name} is not found in runtime class $qualifiedName")
                                        ctx.memberFieldIds[param.name] = fieldId
                                    }
                                }
                                compileClassInfos[qualifiedName] = existingExternInfo
                            } else {
                                val baseIds = collectBaseMemberIds(baseSpecs.map { it.name })
                                ctx.memberFieldIds.putAll(baseIds.fieldIds)
                                ctx.memberMethodIds.putAll(baseIds.methodIds)
                                ctx.nextFieldId = maxOf(ctx.nextFieldId, baseIds.nextFieldId)
                                ctx.nextMethodId = maxOf(ctx.nextMethodId, baseIds.nextMethodId)
                                for (member in ctx.declaredMembers) {
                                    val isOverride = ctx.memberOverrides[member] == true
                                    val hasBaseField = member in baseIds.fieldIds
                                    val hasBaseMethod = member in baseIds.methodIds
                                    if (isOverride) {
                                        if (!hasBaseField && !hasBaseMethod) {
                                            throw ScriptError(nameToken.pos, "member $member is marked 'override' but does not override anything")
                                        }
                                    } else {
                                        if (hasBaseField || hasBaseMethod) {
                                            throw ScriptError(nameToken.pos, "member $member overrides parent member but 'override' keyword is missing")
                                        }
                                    }
                                }
                                constructorArgsDeclaration?.params?.forEach { param ->
                                    if (param.accessType == null) return@forEach
                                    if (!ctx.memberFieldIds.containsKey(param.name)) {
                                        ctx.memberFieldIds[param.name] = ctx.nextFieldId++
                                    }
                                }
                                compileClassInfos[qualifiedName] = CompileClassInfo(
                                    name = qualifiedName,
                                    packageName = packageName,
                                    fieldIds = ctx.memberFieldIds.toMap(),
                                    methodIds = ctx.memberMethodIds.toMap(),
                                    nextFieldId = ctx.nextFieldId,
                                    nextMethodId = ctx.nextMethodId,
                                    baseNames = baseSpecs.map { it.name }
                                )
                            }
                        }
                        val parsed = withLocalNames(constructorArgsDeclaration?.params?.map { it.name }?.toSet() ?: emptySet()) {
                            parseScript()
                        }
                        if (!isExtern) {
                            classCtx?.let { ctx ->
                                compileClassInfos[qualifiedName] = CompileClassInfo(
                                    name = qualifiedName,
                                    packageName = packageName,
                                    fieldIds = ctx.memberFieldIds.toMap(),
                                    methodIds = ctx.memberMethodIds.toMap(),
                                    nextFieldId = ctx.nextFieldId,
                                    nextMethodId = ctx.nextMethodId,
                                    baseNames = baseSpecs.map { it.name }
                                )
                            }
                        }
                        registerClassScopeFieldType(outerClassName, declaredName, qualifiedName)
                        parsed
                    } finally {
                        slotPlanStack.removeLast()
                        resolutionSink?.exitScope(cc.currentPos())
                    }
                    val rbTok = cc.next()
                    if (rbTok.type != Token.Type.RBRACE) throw ScriptError(rbTok.pos, "unbalanced braces in class body")
                    classBodyRange = MiniRange(bodyStart, rbTok.pos)
                    miniSink?.onExitClass(rbTok.pos)
                    st
                } else {
                    // No body, but still emit the class
                    run {
                        val node = MiniClassDecl(
                            range = MiniRange(startPos, cc.currentPos()),
                            name = declaredName,
                            bases = baseSpecs.map { it.name },
                            bodyRange = null,
                            ctorFields = ctorFields,
                            doc = doc,
                            nameStart = nameToken.pos,
                            isExtern = isExtern
                        )
                        miniSink?.onClassDecl(node)
                    }
                    resolutionSink?.declareClass(qualifiedName, baseSpecs.map { it.name }, startPos)
                    classCtx?.let { ctx ->
                        val existingExternInfo = if (isExtern) resolveCompileClassInfo(qualifiedName) else null
                        if (existingExternInfo != null) {
                            ctx.memberFieldIds.putAll(existingExternInfo.fieldIds)
                            ctx.memberMethodIds.putAll(existingExternInfo.methodIds)
                            ctx.nextFieldId = maxOf(ctx.nextFieldId, existingExternInfo.nextFieldId)
                            ctx.nextMethodId = maxOf(ctx.nextMethodId, existingExternInfo.nextMethodId)
                                for (member in ctx.declaredMembers) {
                                    val hasField = member in existingExternInfo.fieldIds
                                    val hasMethod = member in existingExternInfo.methodIds
                                    if (!hasField && !hasMethod) {
                                        throw ScriptError(nameToken.pos, "extern member $member is not found in runtime class $qualifiedName")
                                    }
                                }
                                constructorArgsDeclaration?.params?.forEach { param ->
                                    if (param.accessType == null) return@forEach
                                    if (!ctx.memberFieldIds.containsKey(param.name)) {
                                        val fieldId = existingExternInfo.fieldIds[param.name]
                                            ?: throw ScriptError(nameToken.pos, "extern field ${param.name} is not found in runtime class $qualifiedName")
                                        ctx.memberFieldIds[param.name] = fieldId
                                    }
                                }
                                compileClassInfos[qualifiedName] = existingExternInfo
                            } else {
                                val baseIds = collectBaseMemberIds(baseSpecs.map { it.name })
                                ctx.memberFieldIds.putAll(baseIds.fieldIds)
                                ctx.memberMethodIds.putAll(baseIds.methodIds)
                                ctx.nextFieldId = maxOf(ctx.nextFieldId, baseIds.nextFieldId)
                                ctx.nextMethodId = maxOf(ctx.nextMethodId, baseIds.nextMethodId)
                            for (member in ctx.declaredMembers) {
                                val isOverride = ctx.memberOverrides[member] == true
                                val hasBaseField = member in baseIds.fieldIds
                                val hasBaseMethod = member in baseIds.methodIds
                                if (isOverride) {
                                    if (!hasBaseField && !hasBaseMethod) {
                                        throw ScriptError(nameToken.pos, "member $member is marked 'override' but does not override anything")
                                    }
                                } else {
                                        if (hasBaseField || hasBaseMethod) {
                                            throw ScriptError(nameToken.pos, "member $member overrides parent member but 'override' keyword is missing")
                                        }
                                    }
                                }
                                constructorArgsDeclaration?.params?.forEach { param ->
                                    if (param.accessType == null) return@forEach
                                    if (!ctx.memberFieldIds.containsKey(param.name)) {
                                        ctx.memberFieldIds[param.name] = ctx.nextFieldId++
                                    }
                                }
                                compileClassInfos[qualifiedName] = CompileClassInfo(
                                    name = qualifiedName,
                                    packageName = packageName,
                                    fieldIds = ctx.memberFieldIds.toMap(),
                                    methodIds = ctx.memberMethodIds.toMap(),
                                    nextFieldId = ctx.nextFieldId,
                                    nextMethodId = ctx.nextMethodId,
                                    baseNames = baseSpecs.map { it.name }
                                )
                        }
                    }
                    registerClassScopeFieldType(outerClassName, declaredName, qualifiedName)
                    // restore if no body starts here
                    cc.restorePos(saved)
                    null
                }
            }

            val initScope = popInitScope()
            val wrappedBodyInit = bodyInit?.let { wrapClassBodyBytecode(it, "class@$qualifiedName") }
            val wrappedInitScope = initScope.map { wrapClassBodyBytecode(it, "classInit@$qualifiedName") }

            // create class
            val className = qualifiedName

//        @Suppress("UNUSED_VARIABLE") val defaultAccess = if (isStruct) AccessType.Variable else AccessType.Initialization
//        @Suppress("UNUSED_VARIABLE") val defaultVisibility = Visibility.Public

            // create instance constructor
            // create custom objClass with all fields and instance constructor

            val classInfo = compileClassInfos[className]
            val spec = ClassDeclSpec(
                declaredName = declaredName,
                className = className,
                typeName = className,
                startPos = startPos,
                isExtern = isExtern,
                isAbstract = isAbstract,
                isClosed = isClosed,
                isObject = false,
                isAnonymous = false,
                baseSpecs = baseSpecs.map { ClassDeclBaseSpec(it.name, it.args) },
                constructorArgs = constructorArgsDeclaration,
                constructorFieldIds = classInfo?.fieldIds,
                bodyInit = wrappedBodyInit,
                initScope = wrappedInitScope
            )
            ClassDeclStatement(spec)
        }

    }


    private fun getLabel(maxDepth: Int = 2): String? {
        var cnt = 0
        var found: String? = null
        while (cc.hasPrevious() && cnt < maxDepth) {
            val t = cc.previous()
            cnt++
            if (t.type == Token.Type.LABEL || t.type == Token.Type.ATLABEL) {
                found = t.value
                break
            }
        }
        while (cnt-- > 0) cc.next()
        return found
    }

    private suspend fun parseForStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val start = ensureLparen()

        val tVar = cc.next()
        if (tVar.type != Token.Type.ID)
            throw ScriptError(tVar.pos, "Bad for statement: expected loop variable")
        val tOp = cc.next()
        if (tOp.value == "in") {
            // in loop
            // We must parse an expression here. Using parseStatement() would treat a leading '{'
            // as a block, breaking inline map literals like: for (i in {foo: "bar"}) { ... }
            // So we parse an expression explicitly and wrap it into a StatementRef.
            val exprAfterIn = parseExpression() ?: throw ScriptError(start, "Bad for statement: expected expression")
            val source: Statement = exprAfterIn
            val constRange = (exprAfterIn as? ExpressionStatement)?.ref?.let { ref ->
                constIntRangeOrNull(ref)
            }
            ensureRparen()

            // Expose the loop variable name to the parser so identifiers inside the loop body
            // can be emitted as FastLocalVarRef when enabled.
            val namesForLoop = (currentLocalNames?.toSet() ?: emptySet()) + tVar.value
            val loopSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
            slotPlanStack.add(loopSlotPlan)
            declareSlotName(tVar.value, isMutable = true, isDelegated = false)
            val loopSlotIndex = loopSlotPlan.slots[tVar.value]?.index
            val loopVarTypeDecl = inferForLoopElementType(source, constRange)
            val hadLoopNameType = nameTypeDecl.containsKey(tVar.value)
            val prevLoopNameType = nameTypeDecl[tVar.value]
            val hadLoopNameClass = nameObjClass.containsKey(tVar.value)
            val prevLoopNameClass = nameObjClass[tVar.value]
            if (loopSlotIndex != null && loopVarTypeDecl != null) {
                slotTypeDeclByScopeId.getOrPut(loopSlotPlan.id) { mutableMapOf() }[loopSlotIndex] = loopVarTypeDecl
                nameTypeDecl[tVar.value] = loopVarTypeDecl
                resolveTypeDeclObjClass(loopVarTypeDecl)?.let { loopVarClass ->
                    slotTypeByScopeId.getOrPut(loopSlotPlan.id) { mutableMapOf() }[loopSlotIndex] = loopVarClass
                    nameObjClass[tVar.value] = loopVarClass
                }
            }
            val (canBreak, body, elseStatement) = try {
                resolutionSink?.enterScope(ScopeKind.BLOCK, tVar.pos, null)
                resolutionSink?.declareSymbol(tVar.value, SymbolKind.LOCAL, isMutable = true, pos = tVar.pos)
                withLocalNames(namesForLoop) {
                    val loopParsed = cc.parseLoop {
                        if (cc.current().type == Token.Type.LBRACE) parseBlock()
                        else parseStatement() ?: throw ScriptError(start, "Bad for statement: expected loop body")
                    }
                    // possible else clause
                    cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                    val elseStmt = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
                        parseStatement()
                    } else {
                        cc.previous()
                        null
                    }
                    Triple(loopParsed.first, loopParsed.second, elseStmt)
                }
            } finally {
                if (hadLoopNameType) {
                    nameTypeDecl[tVar.value] = prevLoopNameType!!
                } else {
                    nameTypeDecl.remove(tVar.value)
                }
                if (hadLoopNameClass) {
                    nameObjClass[tVar.value] = prevLoopNameClass!!
                } else {
                    nameObjClass.remove(tVar.value)
                }
                resolutionSink?.exitScope(cc.currentPos())
                slotPlanStack.removeLast()
            }
            val loopSlotPlanSnapshot = slotPlanIndices(loopSlotPlan)

            return ForInStatement(
                loopVarName = tVar.value,
                source = source,
                constRange = constRange,
                body = body,
                elseStatement = elseStatement,
                label = label,
                canBreak = canBreak,
                loopSlotPlan = loopSlotPlanSnapshot,
                loopScopeId = loopSlotPlan.id,
                pos = body.pos
            )
        } else {
            // maybe other loops?
            throw ScriptError(tOp.pos, "Unsupported for-loop syntax")
        }
    }

    private fun constIntRangeOrNull(ref: ObjRef): ConstIntRange? {
        when (ref) {
            is ConstRef -> {
                val range = ref.constValue as? ObjRange ?: return null
                if (!range.isIntRange) return null
                if (range.step != null && !range.step.isNull) return null
                val start = range.start?.toLong() ?: return null
                val end = range.end?.toLong() ?: return null
                val endExclusive = if (range.isEndInclusive) end + 1 else end
                return ConstIntRange(start, endExclusive)
            }
            is RangeRef -> {
                if (ref.step != null) return null
                val start = constIntValueOrNull(ref.left) ?: return null
                val end = constIntValueOrNull(ref.right) ?: return null
                val endExclusive = if (ref.isEndInclusive) end + 1 else end
                return ConstIntRange(start, endExclusive)
            }
            else -> return null
        }
    }

    private fun constIntValueOrNull(ref: ObjRef?): Long? {
        return when (ref) {
            is ConstRef -> (ref.constValue as? ObjInt)?.value
            is StatementRef -> {
                val stmt = ref.statement
                if (stmt is ExpressionStatement) constIntValueOrNull(stmt.ref) else null
            }
            else -> null
        }
    }

    @Suppress("UNUSED_VARIABLE")
    private suspend fun parseDoWhileStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val loopSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        slotPlanStack.add(loopSlotPlan)
        var conditionSlotPlan: SlotPlan = loopSlotPlan
        val (canBreak, parsedBody) = try {
            cc.parseLoop {
                if (cc.current().type == Token.Type.LBRACE) {
                    val (blockStmt, blockPlan) = parseLoopBlockWithPlan()
                    conditionSlotPlan = blockPlan
                    blockStmt
                } else {
                    parseStatement() ?: throw ScriptError(cc.currentPos(), "Bad do-while statement: expected body statement")
                }
            }
        } finally {
            slotPlanStack.removeLast()
        }
        label?.also { cc.labels -= it }
        val body = unwrapBytecodeDeep(parsedBody)

        cc.skipWsTokens()
        val tWhile = cc.next()
        if (tWhile.type != Token.Type.ID || tWhile.value != "while")
            throw ScriptError(tWhile.pos, "Expected 'while' after do body")

        ensureLparen()
        slotPlanStack.add(conditionSlotPlan)
        val condition = try {
            parseExpression() ?: throw ScriptError(cc.currentPos(), "Expected condition after 'while'")
        } finally {
            slotPlanStack.removeLast()
        }
        ensureRparen()

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
            val parsedElse = parseStatement()
            parsedElse?.let { unwrapBytecodeDeep(it) }
        } else {
            cc.previous()
            null
        }
        val loopPlanSnapshot = slotPlanIndices(conditionSlotPlan)
        return DoWhileStatement(body, condition, elseStatement, label, loopPlanSnapshot, body.pos)
    }

    private suspend fun parseWhileStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val start = ensureLparen()
        val condition =
            parseExpression() ?: throw ScriptError(start, "Bad while statement: expected expression")
        ensureRparen()

        val loopSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        slotPlanStack.add(loopSlotPlan)
        val (canBreak, parsedBody) = try {
            cc.parseLoop {
                if (cc.current().type == Token.Type.LBRACE) parseLoopBlock()
                else parseStatement() ?: throw ScriptError(start, "Bad while statement: expected statement")
            }
        } finally {
            slotPlanStack.removeLast()
        }
        label?.also { cc.labels -= it }
        val body = unwrapBytecodeDeep(parsedBody)

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
            val parsedElse = parseStatement()
            parsedElse?.let { unwrapBytecodeDeep(it) }
        } else {
            cc.previous()
            null
        }
        val loopPlanSnapshot = slotPlanIndices(loopSlotPlan)
        return WhileStatement(condition, body, elseStatement, label, canBreak, loopPlanSnapshot, body.pos)
    }

    private suspend fun parseBreakStatement(start: Pos): Statement {
        var t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }?.also {
            // check that label is defined
            cc.ensureLabelIsValid(start, it)
        }

        // expression?
        t = cc.next()
        cc.previous()
        val resultExpr = if (t.pos.line == start.line && (!t.isComment &&
                    t.type != Token.Type.SEMICOLON &&
                    t.type != Token.Type.NEWLINE &&
                    t.type != Token.Type.RBRACE &&
                    t.type != Token.Type.RPAREN &&
                    t.type != Token.Type.RBRACKET &&
                    t.type != Token.Type.COMMA &&
                    t.type != Token.Type.EOF)
        ) {
            // we have something on this line, could be expression
            parseStatement()
        } else null

        cc.addBreak()

        return BreakStatement(label, resultExpr, start)
    }

    private fun parseContinueStatement(start: Pos): Statement {
        val t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }?.also {
            // check that label is defined
            cc.ensureLabelIsValid(start, it)
        }
        cc.addBreak()

        return ContinueStatement(label, start)
    }

    private suspend fun parseReturnStatement(start: Pos): Statement {
        var t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }

        // expression?
        t = cc.next()
        cc.previous()
        val resultExpr = if (t.pos.line == start.line && (!t.isComment &&
                    t.type != Token.Type.SEMICOLON &&
                    t.type != Token.Type.NEWLINE &&
                    t.type != Token.Type.RBRACE &&
                    t.type != Token.Type.RPAREN &&
                    t.type != Token.Type.RBRACKET &&
                    t.type != Token.Type.COMMA &&
                    t.type != Token.Type.EOF)
        ) {
            // we have something on this line, could be expression
            parseExpression()
        } else null

        return ReturnStatement(label, resultExpr, start)
    }

    private fun ensureRparen(): Pos {
        val t = cc.next()
        if (t.type != Token.Type.RPAREN)
            throw ScriptError(t.pos, "expected ')'")
        return t.pos
    }

    private fun ensureLparen(): Pos {
        val t = cc.next()
        if (t.type != Token.Type.LPAREN)
            throw ScriptError(t.pos, "expected '('")
        return t.pos
    }

    private data class SmartCastTarget(
        val name: String? = null,
        val scopeId: Int? = null,
        val slot: Int? = null,
        val typeDecl: TypeDecl,
    )

    private data class SmartCastRestore(
        val name: String?,
        val nameHad: Boolean,
        val namePrev: TypeDecl?,
        val scopeId: Int?,
        val slot: Int?,
        val slotHad: Boolean,
        val slotPrev: TypeDecl?,
        val slotMapCreated: Boolean,
    )

    private fun smartCastTargetFromRef(ref: ObjRef, typeDecl: TypeDecl): SmartCastTarget? = when (ref) {
        is LocalSlotRef -> {
            val ownerScopeId = ref.captureOwnerScopeId ?: ref.scopeId
            val ownerSlot = ref.captureOwnerSlot ?: ref.slot
            SmartCastTarget(scopeId = ownerScopeId, slot = ownerSlot, typeDecl = typeDecl)
        }
        is LocalVarRef -> SmartCastTarget(name = ref.name, typeDecl = typeDecl)
        is FastLocalVarRef -> SmartCastTarget(name = ref.name, typeDecl = typeDecl)
        else -> null
    }

    private fun extractSmartCasts(condition: Statement): Pair<List<SmartCastTarget>, List<SmartCastTarget>> {
        val ref = unwrapDirectRef(condition) ?: return emptyList<SmartCastTarget>() to emptyList()
        if (ref !is BinaryOpRef) return emptyList<SmartCastTarget>() to emptyList()
        if (ref.op != BinOp.IS && ref.op != BinOp.NOTIS) return emptyList<SmartCastTarget>() to emptyList()
        val typeRef = ref.right as? net.sergeych.lyng.obj.TypeDeclRef ?: return emptyList<SmartCastTarget>() to emptyList()
        val typeDecl = expandTypeAliases(typeRef.decl(), typeRef.pos())
        val target = smartCastTargetFromRef(ref.left, typeDecl) ?: return emptyList<SmartCastTarget>() to emptyList()
        return if (ref.op == BinOp.IS) {
            listOf(target) to emptyList()
        } else {
            emptyList<SmartCastTarget>() to listOf(target)
        }
    }

    private fun applySmartCasts(overrides: List<SmartCastTarget>): List<SmartCastRestore> {
        if (overrides.isEmpty()) return emptyList()
        val restores = ArrayList<SmartCastRestore>(overrides.size)
        for (override in overrides) {
            if (override.name != null) {
                val had = nameTypeDecl.containsKey(override.name)
                val prev = nameTypeDecl[override.name]
                nameTypeDecl[override.name] = override.typeDecl
                restores.add(
                    SmartCastRestore(
                        name = override.name,
                        nameHad = had,
                        namePrev = prev,
                        scopeId = null,
                        slot = null,
                        slotHad = false,
                        slotPrev = null,
                        slotMapCreated = false,
                    )
                )
            } else if (override.scopeId != null && override.slot != null) {
                val map = slotTypeDeclByScopeId[override.scopeId]
                val mapCreated = map == null
                val targetMap = map ?: mutableMapOf<Int, TypeDecl>().also { slotTypeDeclByScopeId[override.scopeId] = it }
                val had = targetMap.containsKey(override.slot)
                val prev = targetMap[override.slot]
                targetMap[override.slot] = override.typeDecl
                restores.add(
                    SmartCastRestore(
                        name = null,
                        nameHad = false,
                        namePrev = null,
                        scopeId = override.scopeId,
                        slot = override.slot,
                        slotHad = had,
                        slotPrev = prev,
                        slotMapCreated = mapCreated,
                    )
                )
            }
        }
        return restores
    }

    private fun restoreSmartCasts(restores: List<SmartCastRestore>) {
        if (restores.isEmpty()) return
        for (restore in restores.asReversed()) {
            if (restore.name != null) {
                if (restore.nameHad) {
                    nameTypeDecl[restore.name] = restore.namePrev!!
                } else {
                    nameTypeDecl.remove(restore.name)
                }
            } else if (restore.scopeId != null && restore.slot != null) {
                val map = slotTypeDeclByScopeId[restore.scopeId]
                if (map != null) {
                    if (restore.slotHad) {
                        map[restore.slot] = restore.slotPrev!!
                    } else {
                        map.remove(restore.slot)
                    }
                    if (restore.slotMapCreated && map.isEmpty()) {
                        slotTypeDeclByScopeId.remove(restore.scopeId)
                    }
                }
            }
        }
    }

    private fun applyAssertSmartCasts(statement: Statement) {
        val ref = unwrapDirectRef(statement) as? CallRef ?: return
        val targetName = when (val target = ref.target) {
            is LocalVarRef -> target.name
            is FastLocalVarRef -> target.name
            is LocalSlotRef -> target.name
            else -> null
        } ?: return
        if (targetName != "assert") return
        val conditionArg = ref.args.firstOrNull { !it.isSplat && it.name == null }
            ?: ref.args.firstOrNull { !it.isSplat && it.name == "condition" }
            ?: return
        val conditionStatement = when (val argValue = conditionArg.value) {
            is Statement -> argValue
            is ObjRef -> ExpressionStatement(argValue, conditionArg.pos)
            else -> return
        }
        val (trueCasts, _) = extractSmartCasts(conditionStatement)
        applySmartCasts(trueCasts)
    }

    private suspend fun parseIfStatement(): Statement {
        val start = ensureLparen()

        val condition = parseExpression()
            ?: throw ScriptError(start, "Bad if statement: expected expression")

        val pos = ensureRparen()

        val (trueCasts, falseCasts) = extractSmartCasts(condition)
        val ifRestores = applySmartCasts(trueCasts)
        val ifBody = try {
            parseStatement() ?: throw ScriptError(pos, "Bad if statement: expected statement")
        } finally {
            restoreSmartCasts(ifRestores)
        }

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        // could be else block:
        val t2 = cc.nextNonWhitespace()

        // we generate different statements: optimization
        val stmt = if (t2.type == Token.Type.ID && t2.value == "else") {
            val elseRestores = applySmartCasts(falseCasts)
            val elseBody = try {
                parseStatement() ?: throw ScriptError(pos, "Bad else statement: expected statement")
            } finally {
                restoreSmartCasts(elseRestores)
            }
            IfStatement(condition, ifBody, elseBody, start)
        } else {
            cc.previous()
            IfStatement(condition, ifBody, null, start)
        }
        return wrapBytecode(stmt)
    }

    private suspend fun parseFunctionDeclaration(
        visibility: Visibility = Visibility.Public,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isExtern: Boolean = false,
        isStatic: Boolean = false,
        isTransient: Boolean = isTransientFlag
    ): Statement {
        isTransientFlag = false
        val actualExtern = isExtern || (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.isExtern == true
        var start = cc.currentPos()
        var extTypeName: String? = null
        var receiverTypeDecl: TypeDecl? = null
        var name: String
        var nameStartPos: Pos
        var receiverMini: MiniTypeRef? = null

        val annotation = lastAnnotation
        val parentContext = codeContexts.last()

        // Is extension?
        if (looksLikeExtensionReceiver()) {
            val (recvDecl, recvMini) = parseExtensionReceiverTypeWithMini()
            receiverTypeDecl = recvDecl
            receiverMini = recvMini
            val dot = cc.nextNonWhitespace()
            if (dot.type != Token.Type.DOT) {
                throw ScriptError(dot.pos, "illegal extension format: expected '.' after receiver type")
            }
            val t = cc.next()
            if (t.type != Token.Type.ID) {
                throw ScriptError(t.pos, "illegal extension format: expected function name")
            }
            name = t.value
            nameStartPos = t.pos
            extTypeName = when (recvDecl) {
                is TypeDecl.Simple -> recvDecl.name.substringAfterLast('.')
                is TypeDecl.Generic -> recvDecl.name.substringAfterLast('.')
                else -> throw ScriptError(
                    recvMini.range.start,
                    "illegal extension receiver type: ${typeDeclName(recvDecl)}"
                )
            }
            registerExtensionName(extTypeName, name)
        } else {
            val t = cc.next()
            if (t.type != Token.Type.ID)
                throw ScriptError(t.pos, "Expected identifier after 'fun'")
            start = t.pos
            name = t.value
            nameStartPos = t.pos
        }
        val extensionWrapperName = extTypeName?.let { extensionCallableName(it, name) }
        val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        var memberMethodId = if (extTypeName == null) classCtx?.memberMethodIds?.get(name) else null
        val externCallSignature = if (actualExtern) importManager.rootScope.getLocalRecordDirect(name)?.callSignature else null

        val declKind = if (parentContext is CodeContext.ClassBody) SymbolKind.MEMBER else SymbolKind.FUNCTION
        resolutionSink?.declareSymbol(name, declKind, isMutable = false, pos = nameStartPos, isOverride = isOverride)
        if (parentContext is CodeContext.ClassBody && extTypeName == null) {
            if (isStatic) {
                parentContext.classScopeMembers.add(name)
                registerClassScopeMember(parentContext.name, name)
                registerClassScopeCallableMember(parentContext.name, name)
            } else {
                parentContext.declaredMembers.add(name)
                if (!parentContext.memberMethodIds.containsKey(name)) {
                    parentContext.memberMethodIds[name] = parentContext.nextMethodId++
                }
                memberMethodId = parentContext.memberMethodIds[name]
            }
        }
        if (declKind != SymbolKind.MEMBER) {
            declareLocalName(name, isMutable = false)
        }
        if (extensionWrapperName != null) {
            declareLocalName(extensionWrapperName, isMutable = false)
        }
        if (actualExtern && declKind != SymbolKind.MEMBER) {
            externCallableNames.add(name)
        }
        if (actualExtern && extensionWrapperName != null) {
            externCallableNames.add(extensionWrapperName)
        }
        val declSlotPlan = if (declKind != SymbolKind.MEMBER) slotPlanStack.lastOrNull() else null
        val declSlotIndex = declSlotPlan?.slots?.get(name)?.index
        val declScopeId = declSlotPlan?.id

        val typeParamDecls = parseTypeParamList()
        val explicitTypeParams = typeParamDecls.map { it.name }.toSet()
        val receiverNormalization = normalizeReceiverTypeDecl(receiverTypeDecl, explicitTypeParams)
        receiverTypeDecl = receiverNormalization.first
        val implicitTypeParams = receiverNormalization.second
        val mergedTypeParamDecls = if (implicitTypeParams.isEmpty()) {
            typeParamDecls
        } else {
            typeParamDecls + implicitTypeParams.filter { it !in explicitTypeParams }
                .map { TypeDecl.TypeParam(it) }
        }
        val typeParams = mergedTypeParamDecls.map { it.name }.toSet()
        pendingTypeParamStack.add(typeParams)
        var argsDeclaration: ArgsDeclaration
        val returnTypeMini: MiniTypeRef?
        val returnTypeDecl: TypeDecl?
        try {
            argsDeclaration =
                if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                    cc.nextNonWhitespace() // consume (
                    parseArgsDeclaration() ?: ArgsDeclaration(emptyList(), Token.Type.RPAREN)
                } else ArgsDeclaration(emptyList(), Token.Type.RPAREN)

            if (declKind != SymbolKind.MEMBER) {
                currentGenericFunctionDecls()[name] = GenericFunctionDecl(mergedTypeParamDecls, argsDeclaration.params, nameStartPos)
            }

            // Optional return type
            val parsedReturn = if (cc.peekNextNonWhitespace().type == Token.Type.COLON) {
                parseTypeDeclarationWithMini()
            } else null
            returnTypeMini = parsedReturn?.second
            returnTypeDecl = parsedReturn?.first
        } finally {
            pendingTypeParamStack.removeLast()
        }

        var isDelegated = false
        var delegateExpression: Statement? = null
        if (cc.peekNextNonWhitespace().type == Token.Type.BY) {
            cc.nextNonWhitespace() // consume by
            isDelegated = true
            delegateExpression = parseExpression() ?: throw ScriptError(cc.current().pos, "Expected delegate expression")
            if (compileBytecode) {
                delegateExpression = wrapFunctionBytecode(delegateExpression, "delegate@$name")
            }
            if (declKind != SymbolKind.MEMBER) {
                currentGenericFunctionDecls().remove(name)
            }
        }
        if (isDelegated && declKind != SymbolKind.MEMBER) {
            val plan = slotPlanStack.lastOrNull()
            val entry = plan?.slots?.get(name)
            if (entry != null && !entry.isDelegated) {
                plan.slots[name] = SlotEntry(entry.index, entry.isMutable, isDelegated = true)
            }
        }

        if (!isDelegated && argsDeclaration.endTokenType != Token.Type.RPAREN)
            throw ScriptError(
                nameStartPos,
                "Bad function definition: expected valid argument declaration or () after 'fn ${name}'"
            )

        // Capture doc locally to reuse even if we need to emit later
        val declDocLocal = pendingDeclDoc
        val outerLabel = lastLabel

        val node = run {
            val params = argsDeclaration.params.map { p ->
                MiniParam(
                    name = p.name,
                    type = p.miniType,
                    nameStart = p.pos
                )
            }
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val node = MiniFunDecl(
                range = declRange,
                name = name,
                params = params,
                returnType = returnTypeMini,
                body = null,
                doc = declDocLocal,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onFunDecl(node)
            pendingDeclDoc = null
            node
        }

        miniSink?.onEnterFunction(node)
        val implicitThisMembers = extTypeName != null || (parentContext is CodeContext.ClassBody && !isStatic)
        val implicitThisTypeName = when {
            extTypeName != null -> extTypeName
            parentContext is CodeContext.ClassBody && !isStatic -> parentContext.name
            else -> null
        }
        return inCodeContext(
            CodeContext.Function(
                name,
                implicitThisMembers = implicitThisMembers,
                implicitThisTypeName = implicitThisTypeName,
                typeParams = typeParams,
                typeParamDecls = typeParamDecls
            )
        ) {
            cc.labels.add(name)
            outerLabel?.let { cc.labels.add(it) }

            val paramNamesList = argsDeclaration.params.map { it.name }
            val typeParamNames = mergedTypeParamDecls.map { it.name }
            val paramNames: Set<String> = paramNamesList.toSet()
            val paramSlotPlan = buildParamSlotPlan(paramNamesList + typeParamNames)
            val paramSlotPlanSnapshot = slotPlanIndices(paramSlotPlan)
            val forcedLocalSlots = LinkedHashMap<String, Int>()
            for (name in paramNamesList) {
                val idx = paramSlotPlanSnapshot[name] ?: continue
                forcedLocalSlots[name] = idx
            }
            for (name in typeParamNames) {
                val idx = paramSlotPlanSnapshot[name] ?: continue
                forcedLocalSlots[name] = idx
            }
            argsDeclaration = wrapDefaultArgsBytecode(argsDeclaration, forcedLocalSlots, paramSlotPlan.id)
            val capturePlan = CapturePlan(paramSlotPlan, isFunction = true, propagateToParentFunction = false)
            val rangeParamNames = argsDeclaration.params
                .filter { isRangeType(it.type) }
                .map { it.name }
                .toSet()
            val paramTypeMap = slotTypeByScopeId.getOrPut(paramSlotPlan.id) { mutableMapOf() }
            val paramTypeDeclMap = slotTypeDeclByScopeId.getOrPut(paramSlotPlan.id) { mutableMapOf() }
            for (param in argsDeclaration.params) {
                val cls = resolveTypeDeclObjClass(param.type) ?: continue
                val slot = paramSlotPlan.slots[param.name]?.index ?: continue
                paramTypeMap[slot] = cls
            }
            for (param in argsDeclaration.params) {
                val slot = paramSlotPlan.slots[param.name]?.index ?: continue
                paramTypeDeclMap[slot] = param.type
            }

            // Parse function body while tracking declared locals to compute precise capacity hints
            currentLocalDeclCount
            localDeclCountStack.add(0)
            slotPlanStack.add(paramSlotPlan)
            capturePlanStack.add(capturePlan)
            rangeParamNamesStack.add(rangeParamNames)
            resolutionSink?.enterScope(ScopeKind.FUNCTION, start, name)
            for (param in argsDeclaration.params) {
                resolutionSink?.declareSymbol(param.name, SymbolKind.PARAM, isMutable = false, pos = param.pos)
            }
            val parsedFnStatements = try {
                val returnLabels = buildSet {
                    add(name)
                    outerLabel?.let { add(it) }
                }
                returnLabelStack.addLast(returnLabels)
                try {
                    if (actualExtern) {
                        val msg = ObjString("extern function not provided: $name").asReadonly
                        val expr = ExpressionStatement(ConstRef(msg), start)
                        ThrowStatement(expr, start)
                    }
                    else if (isAbstract || isDelegated) {
                        null
                    } else
                        withLocalNames(paramNames) {
                            val next = cc.peekNextNonWhitespace()
                            if (next.type == Token.Type.ASSIGN) {
                                cc.nextNonWhitespace() // consume '='
                                if (cc.peekNextNonWhitespace().value == "return")
                                    throw ScriptError(cc.currentPos(), "return is not allowed in shorthand function")
                                val exprStmt = parseExpression()
                                    ?: throw ScriptError(cc.currentPos(), "Expected function body expression")
                                // Shorthand function returns the expression value.
                                exprStmt
                            } else {
                                parseBlock()
                            }
                    }
                } finally {
                    returnLabelStack.removeLast()
                }
            } finally {
                rangeParamNamesStack.removeLast()
                capturePlanStack.removeLast()
                slotPlanStack.removeLast()
                resolutionSink?.exitScope(cc.currentPos())
            }
            val rawFnStatements = parsedFnStatements?.let { unwrapBytecodeDeep(it) }
            val inferredReturnClass = returnTypeDecl?.let { resolveTypeDeclObjClass(it) }
                ?: inferReturnClassFromStatement(rawFnStatements)
            if (declKind == SymbolKind.MEMBER && extTypeName == null) {
                val ownerClassName = (parentContext as? CodeContext.ClassBody)?.name
                if (ownerClassName != null) {
                    val returnDecl = returnTypeDecl
                        ?: inferredReturnClass?.let { TypeDecl.Simple(it.className, false) }
                    if (returnDecl != null) {
                        classMethodReturnTypeDeclByName
                            .getOrPut(ownerClassName) { mutableMapOf() }[name] = returnDecl
                        resolveTypeDeclObjClass(returnDecl)?.let { returnClass ->
                            classMethodReturnTypeByName
                                .getOrPut(ownerClassName) { mutableMapOf() }[name] = returnClass
                            classFieldTypesByName
                                .getOrPut(ownerClassName) { mutableMapOf() }[name] = returnClass
                        }
                    }
                }
            }
            if (declKind != SymbolKind.MEMBER && inferredReturnClass != null) {
                callableReturnTypeByName[name] = inferredReturnClass
                val slotLoc = lookupSlotLocation(name, includeModule = true)
                if (slotLoc != null) {
                    callableReturnTypeByScopeId.getOrPut(slotLoc.scopeId) { mutableMapOf() }[slotLoc.slot] =
                        inferredReturnClass
                }
            }
            val inferredReturnDecl = returnTypeDecl ?: inferredReturnClass?.let { TypeDecl.Simple(it.className, false) }
            if (declKind != SymbolKind.MEMBER && inferredReturnDecl != null) {
                callableReturnTypeDeclByName[name] = inferredReturnDecl
            }
            val fnStatements = rawFnStatements?.let { stmt ->
                if (!compileBytecode) return@let stmt
                val paramKnownClasses = mutableMapOf<String, ObjClass>()
                for (param in argsDeclaration.params) {
                    val cls = resolveTypeDeclObjClass(param.type) ?: continue
                    paramKnownClasses[param.name] = cls
                }
                wrapFunctionBytecode(
                    stmt,
                    name,
                    paramKnownClasses,
                    forcedLocalSlots = forcedLocalSlots,
                    forcedLocalScopeId = paramSlotPlan.id
                )
            }
            // Capture and pop the local declarations count for this function
            val fnLocalDecls = localDeclCountStack.removeLastOrNull() ?: 0

            val closureBox = FunctionClosureBox()

            val captureSlots = capturePlan.captures.toList()
            val fnBody = object : Statement(), BytecodeBodyProvider {
                override val pos: Pos = start
                override fun bytecodeBody(): BytecodeStatement? = fnStatements as? BytecodeStatement
                override suspend fun execute(scope: Scope): Obj {
                    scope.pos = start

                    // restore closure where the function was defined, and making a copy of it
                    // for local space. If there is no closure, we are in, say, class context where
                    // the closure is in the class initialization and we needn't more:
                    val context = closureBox.closure?.let { closure ->
                        scope.applyClosureForBytecode(closure).also {
                            it.args = scope.args
                        }
                    } ?: scope

                    // Capacity hint: parameters + declared locals + small overhead
                    val capacityHint = paramNames.size + fnLocalDecls + 4
                    context.hintLocalCapacity(capacityHint)
                    val captureBase = closureBox.captureContext ?: closureBox.closure
                    val bytecodeBody = (fnStatements as? BytecodeStatement)
                        ?: context.raiseIllegalState("non-bytecode function body encountered")
                    val bytecodeFn = bytecodeBody.bytecodeFunction()
                    val declaredNames = bytecodeFn.constants
                        .mapNotNull { it as? BytecodeConst.LocalDecl }
                        .mapTo(mutableSetOf()) { it.name }
                    val captureNames = if (captureSlots.isNotEmpty()) {
                        captureSlots.map { it.name }
                    } else {
                        val fn = bytecodeFn
                        val names = fn.localSlotNames
                        val captures = fn.localSlotCaptures
                        val ordered = LinkedHashSet<String>()
                        for (i in names.indices) {
                            if (captures.getOrNull(i) != true) continue
                            val name = names[i] ?: continue
                            ordered.add(name)
                        }
                        ordered.toList()
                    }
                    val prebuiltCaptures = closureBox.captureRecords
                    if (prebuiltCaptures != null && captureNames.isNotEmpty()) {
                        context.captureRecords = prebuiltCaptures
                        context.captureNames = captureNames
                    } else if (captureBase != null && captureNames.isNotEmpty()) {
                        val resolvedRecords = ArrayList<ObjRecord>(captureNames.size)
                        for (name in captureNames) {
                            val rec = captureBase.chainLookupIgnoreClosure(
                                name,
                                followClosure = true,
                                caller = context.currentClassCtx
                            ) ?: captureBase.raiseSymbolNotFound("symbol $name not found")
                            resolvedRecords.add(rec)
                        }
                        context.captureRecords = resolvedRecords
                        context.captureNames = captureNames
                    }
                    val binder: suspend (net.sergeych.lyng.bytecode.CmdFrame, Arguments) -> Unit = { frame, arguments ->
                        val slotPlan = bytecodeFn.localSlotPlanByName()
                        argsDeclaration.assignToFrame(
                            context,
                            arguments,
                            slotPlan,
                            frame.frame
                        )
                        val typeBindings = bindTypeParamsAtRuntime(context, argsDeclaration, mergedTypeParamDecls)
                        if (typeBindings.isNotEmpty()) {
                            for ((name, bound) in typeBindings) {
                                val slot = slotPlan[name] ?: continue
                                frame.frame.setObj(slot, bound)
                            }
                        }
                        if (extTypeName != null) {
                            context.thisObj = scope.thisObj
                        }
                        val localNames = frame.fn.localSlotNames
                        for (i in localNames.indices) {
                            val localName = localNames[i] ?: continue
                            if (declaredNames.contains(localName)) continue
                            val slotType = frame.getLocalSlotTypeCode(i)
                            if (slotType != SlotType.UNKNOWN.code && slotType != SlotType.OBJ.code) {
                                continue
                            }
                            if (slotType == SlotType.OBJ.code && frame.frame.getRawObj(i) != null) {
                                continue
                            }
                            val record = context.getLocalRecordDirect(localName)
                                ?: context.parent?.get(localName)
                                ?: context.get(localName)
                                ?: continue
                            val value = if (record.type == ObjRecord.Type.Delegated || record.type == ObjRecord.Type.Property || record.value is ObjProperty) {
                                context.resolve(record, localName)
                            } else {
                                record.value
                            }
                            frame.frame.setObj(i, value)
                        }
                    }
                    return try {
                        net.sergeych.lyng.bytecode.CmdVm().execute(bytecodeFn, context, scope.args, binder)
                    } catch (e: ReturnException) {
                        if (e.label == null || e.label == name || e.label == outerLabel) e.result
                        else throw e
                    }
                }
            }
            cc.labels.remove(name)
            outerLabel?.let { cc.labels.remove(it) }
            val parentIsClassBody = parentContext is CodeContext.ClassBody
            val delegateInitStatement = if (isDelegated && parentIsClassBody && !isStatic && extTypeName == null) {
                val className = currentEnclosingClassName()
                    ?: throw ScriptError(start, "delegated function outside class body")
                val initExpr = delegateExpression ?: throw ScriptError(start, "delegated function missing delegate")
                val storageName = "$className::$name"
                val initStatement = InstanceDelegatedInitStatement(
                    storageName = storageName,
                    memberName = name,
                    isMutable = false,
                    visibility = visibility,
                    writeVisibility = null,
                    isAbstract = isAbstract,
                    isClosed = isClosed,
                    isOverride = isOverride,
                    isTransient = isTransient,
                    accessTypeLabel = "Callable",
                    initializer = initExpr,
                    pos = start
                )
                wrapInstanceInitBytecode(initStatement, "fnDelegate@${storageName}")
            } else null
            val spec = FunctionDeclSpec(
                name = name,
                visibility = visibility,
                isAbstract = isAbstract,
                isClosed = isClosed,
                isOverride = isOverride,
                isStatic = isStatic,
                isTransient = isTransient,
                isDelegated = isDelegated,
                delegateExpression = delegateExpression,
                delegateInitStatement = delegateInitStatement,
                extTypeName = extTypeName,
                extensionWrapperName = extensionWrapperName,
                memberMethodId = memberMethodId,
                actualExtern = actualExtern,
                parentIsClassBody = parentIsClassBody,
                externCallSignature = externCallSignature,
                annotation = annotation,
                typeDecl = if (isDelegated) null else TypeDecl.Function(
                    receiver = receiverTypeDecl,
                    params = argsDeclaration.params.map { it.type },
                    returnType = inferredReturnDecl ?: TypeDecl.TypeAny,
                    nullable = false
                ),
                fnBody = fnBody,
                closureBox = closureBox,
                captureSlots = captureSlots,
                slotIndex = declSlotIndex,
                scopeId = declScopeId,
                startPos = start
            )
            val declaredFn = FunctionDeclStatement(spec)
            if (isStatic) {
                currentInitScope += declaredFn
                NopStatement
            } else
                declaredFn
        }.also {
            val bodyRange = lastParsedBlockRange
            // Also emit a post-parse MiniFunDecl to be robust in case early emission was skipped by some path
            val params = argsDeclaration.params.map { p ->
                MiniParam(
                    name = p.name,
                    type = p.miniType,
                    nameStart = p.pos
                )
            }
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val node = MiniFunDecl(
                range = declRange,
                name = name,
                params = params,
                returnType = returnTypeMini,
                body = bodyRange?.let { MiniBlock(it) },
                doc = declDocLocal,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onExitFunction(cc.currentPos())
            miniSink?.onFunDecl(node)
        }
    }

    private suspend fun parseBlock(skipLeadingBrace: Boolean = false): Statement {
        return parseBlockWithPredeclared(emptyList(), skipLeadingBrace)
    }

    private suspend fun parseExpressionWithPredeclared(
        predeclared: List<Pair<String, Boolean>>
    ): Statement {
        val startPos = cc.currentPos()
        resolutionSink?.enterScope(ScopeKind.BLOCK, startPos, null)
        val exprSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        for ((name, isMutable) in predeclared) {
            declareSlotNameIn(exprSlotPlan, name, isMutable, isDelegated = false)
            resolutionSink?.declareSymbol(name, SymbolKind.LOCAL, isMutable, startPos, isOverride = false)
        }
        slotPlanStack.add(exprSlotPlan)
        val capturePlan = CapturePlan(exprSlotPlan, isFunction = false, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val expr = try {
            parseExpression() ?: throw ScriptError(cc.current().pos, "Expected expression")
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        resolutionSink?.exitScope(cc.currentPos())
        return expr
    }

    private suspend fun parseExpressionBlockWithPredeclared(
        predeclared: List<Pair<String, Boolean>>
    ): Statement {
        val startPos = cc.currentPos()
        resolutionSink?.enterScope(ScopeKind.BLOCK, startPos, null)
        val blockSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        for ((name, isMutable) in predeclared) {
            declareSlotNameIn(blockSlotPlan, name, isMutable, isDelegated = false)
        }
        slotPlanStack.add(blockSlotPlan)
        val capturePlan = CapturePlan(blockSlotPlan, isFunction = false, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val expr = try {
            parseExpression() ?: throw ScriptError(cc.current().pos, "Expected expression")
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        val planSnapshot = slotPlanIndices(blockSlotPlan)
        val block = Script(startPos, listOf(expr))
        val stmt = BlockStatement(block, planSnapshot, blockSlotPlan.id, capturePlan.captures.toList(), startPos)
        resolutionSink?.exitScope(cc.currentPos())
        return stmt
    }

    private fun inferReturnClassFromStatement(stmt: Statement?): ObjClass? {
        if (stmt == null) return null
        val unwrapped = unwrapBytecodeDeep(stmt)
        return when (unwrapped) {
            is ExpressionStatement -> resolveInitializerObjClass(unwrapped)
            is ReturnStatement -> resolveInitializerObjClass(unwrapped.resultExpr)
            is VarDeclStatement -> unwrapped.initializerObjClass ?: resolveInitializerObjClass(unwrapped.initializer)
            is BlockStatement -> {
                val stmts = unwrapped.statements()
                val returnTypes = stmts.mapNotNull { s ->
                    (s as? ReturnStatement)?.let { resolveInitializerObjClass(it.resultExpr) }
                }
                if (returnTypes.isNotEmpty()) {
                    val first = returnTypes.first()
                    if (returnTypes.all { it == first }) first else Obj.rootObjectType
                } else {
                    val last = stmts.lastOrNull()
                    inferReturnClassFromStatement(last)
                }
            }
            is InlineBlockStatement -> {
                val stmts = unwrapped.statements()
                val last = stmts.lastOrNull()
                inferReturnClassFromStatement(last)
            }
            is IfStatement -> {
                val ifType = inferReturnClassFromStatement(unwrapped.ifBody)
                val elseType = unwrapped.elseBody?.let { inferReturnClassFromStatement(it) }
                when {
                    ifType == null && elseType == null -> null
                    ifType != null && elseType != null && ifType == elseType -> ifType
                    else -> Obj.rootObjectType
                }
            }
            else -> null
        }
    }

    private fun unwrapDirectRef(initializer: Statement?): ObjRef? {
        var initStmt = initializer
        while (initStmt is BytecodeStatement) {
            initStmt = initStmt.original
        }
        val initRef = (initStmt as? ExpressionStatement)?.ref
        return when (initRef) {
            is StatementRef -> (initRef.statement as? ExpressionStatement)?.ref ?: initRef
            else -> initRef
        }
    }

    private fun resolveInitializerObjClass(initializer: Statement?): ObjClass? {
        var unwrapped = initializer
        while (unwrapped is BytecodeStatement) {
            val fn = unwrapped.bytecodeFunction()
            if (fn.cmds.any { it is CmdListLiteral }) return ObjList.type
            if (fn.cmds.any { it is CmdMakeRange || it is CmdRangeIntBounds }) return ObjRange.type
            unwrapped = unwrapped.original
        }
        if (unwrapped is DoWhileStatement) {
            val bodyType = inferReturnClassFromStatement(unwrapped.body)
            val elseType = unwrapped.elseStatement?.let { inferReturnClassFromStatement(it) }
            return when {
                bodyType == null && elseType == null -> null
                bodyType != null && elseType != null && bodyType == elseType -> bodyType
                bodyType != null && elseType == null -> bodyType
                bodyType == null -> elseType
                else -> Obj.rootObjectType
            }
        }
        if (unwrapped is ClassDeclStatement) {
            return resolveClassByName(unwrapped.typeName)
        }
        val directRef = unwrapDirectRef(unwrapped)
        return when (directRef) {
            is ConstRef -> when (directRef.constValue) {
                is ObjInt -> ObjInt.type
                is ObjReal -> ObjReal.type
                is ObjBool -> ObjBool.type
                is ObjString -> ObjString.type
                is ObjRange -> ObjRange.type
                is ObjList -> ObjList.type
                is ObjMap -> ObjMap.type
                is ObjChar -> ObjChar.type
                is ObjNull -> Obj.rootObjectType
                is ObjVoid -> ObjVoid.objClass
                else -> null
            }
            is ListLiteralRef -> ObjList.type
            is MapLiteralRef -> ObjMap.type
            is RangeRef -> ObjRange.type
            is StatementRef -> {
                val decl = directRef.statement as? ClassDeclStatement
                decl?.let { resolveClassByName(it.typeName) }
            }
            is ValueFnRef -> lambdaReturnTypeByRef[directRef]
            is ClassOperatorRef -> lambdaReturnTypeByRef[directRef]
            is CastRef -> resolveTypeRefClass(directRef.castTypeRef())
            is BinaryOpRef -> inferBinaryOpReturnClass(directRef)
            is ImplicitThisMethodCallRef -> {
                when (directRef.methodName()) {
                    "iterator" -> ObjIterator
                    "lazy" -> resolveClassByName("lazy") ?: inferMethodCallReturnClass(directRef.methodName())
                    else -> inferMethodCallReturnClass(directRef.methodName())
                }
            }
            is ThisMethodSlotCallRef -> {
                if (directRef.methodName() == "iterator") ObjIterator else null
            }
            is MethodCallRef -> {
                inferMethodCallReturnClass(directRef)
            }
            is FieldRef -> {
                val targetClass = resolveReceiverClassForMember(directRef.target)
                inferFieldReturnClass(targetClass, directRef.name)
            }
            is CallRef -> {
                val target = directRef.target
                when {
                    target is LocalSlotRef -> {
                        when (target.name) {
                            "lazy" -> resolveClassByName("lazy")
                            "iterator" -> ObjIterator
                            "flow" -> ObjFlow.type
                            "launch" -> ObjDeferred.type
                            "dynamic" -> ObjDynamic.type
                            else -> callableReturnTypeByScopeId[target.scopeId]?.get(target.slot)
                                ?: resolveClassByName(target.name)
                        }
                    }
                    target is LocalVarRef -> {
                        when (target.name) {
                            "lazy" -> resolveClassByName("lazy")
                            "iterator" -> ObjIterator
                            "flow" -> ObjFlow.type
                            "launch" -> ObjDeferred.type
                            "dynamic" -> ObjDynamic.type
                            else -> callableReturnTypeByName[target.name]
                                ?: resolveClassByName(target.name)
                        }
                    }
                    target is LocalVarRef && target.name == "List" -> ObjList.type
                    target is LocalVarRef && target.name == "Map" -> ObjMap.type
                    target is LocalVarRef && target.name == "iterator" -> ObjIterator
                    target is ImplicitThisMemberRef && target.name == "iterator" -> ObjIterator
                    target is ThisFieldSlotRef && target.name == "iterator" -> ObjIterator
                    target is FieldRef && target.name == "iterator" -> ObjIterator
                    target is FieldRef -> {
                        val receiverClass = resolveReceiverClassForMember(target.target) ?: return null
                        if (!isClassScopeCallableMember(receiverClass.className, target.name)) return null
                        resolveClassByName("${receiverClass.className}.${target.name}")
                    }
                    target is ConstRef -> when (val value = target.constValue) {
                        is ObjClass -> value
                        is ObjString -> ObjString.type
                        else -> null
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun ensureDelegateType(initializer: Statement) {
        val delegateClass = resolveClassByName("Delegate")
            ?: throw ScriptError(initializer.pos, "Delegate type is not available")
        val initClass = resolveInitializerObjClass(initializer)
            ?: unwrapDirectRef(initializer)?.let { inferObjClassFromRef(it) }
            ?: throw ScriptError(initializer.pos, "Delegate type must be known at compile time")
        if (initClass !== delegateClass &&
            initClass.logicalName != delegateClass.logicalName &&
            !initClass.allParentsSet.contains(delegateClass) &&
            !initClass.allParentsSet.any { it.logicalName == delegateClass.logicalName } &&
            !initClass.allImplementingNames.contains(delegateClass.className)
        ) {
            throw ScriptError(
                initializer.pos,
                "Delegate initializer must return Delegate, got ${initClass.className}"
            )
        }
    }

    private fun resolveTypeDeclObjClass(type: TypeDecl): ObjClass? {
        val rawName = when (type) {
            is TypeDecl.Simple -> type.name
            is TypeDecl.Generic -> type.name
            is TypeDecl.Function -> "Callable"
            is TypeDecl.Ellipsis -> return resolveTypeDeclObjClass(type.elementType)
            is TypeDecl.TypeVar -> return null
            is TypeDecl.Union -> return null
            is TypeDecl.Intersection -> return null
            else -> return null
        }
        val name = rawName.substringAfterLast('.')
        if (!rawName.contains('.')) {
            val classCtx = currentEnclosingClassName()
            if (classCtx != null) {
                resolveClassByName("$classCtx.$rawName")?.let { return it }
            }
        }
        return when (name) {
            "Object", "Obj" -> Obj.rootObjectType
            "String" -> ObjString.type
            "Int" -> ObjInt.type
            "Real" -> ObjReal.type
            "Bool" -> ObjBool.type
            "Char" -> ObjChar.type
            "List" -> ObjList.type
            "ImmutableList" -> ObjImmutableList.type
            "Map" -> ObjMap.type
            "ImmutableMap" -> ObjImmutableMap.type
            "Set" -> ObjSet.type
            "ImmutableSet" -> ObjImmutableSet.type
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
            "Observable" -> ObjObservable
            "Subscription" -> ObjSubscription.type
            "ListChange" -> ObjListChange.type
            "ListSet" -> ObjListSetChange.type
            "ListInsert" -> ObjListInsertChange.type
            "ListRemove" -> ObjListRemoveChange.type
            "ListClear" -> ObjListClearChange.type
            "ListReorder" -> ObjListReorderChange.type
            "ObservableList" -> ObjObservableList.type
            "ChangeRejectionException" -> ObjChangeRejectionExceptionClass
            "Exception" -> ObjException.Root
            "Instant" -> ObjInstant.type
            "DateTime" -> ObjDateTime.type
            "Duration" -> ObjDuration.type
            "Buffer" -> ObjBuffer.type
            "MutableBuffer" -> ObjMutableBuffer.type
            "RingBuffer" -> ObjRingBuffer.type
            "Callable" -> Statement.type
            else -> resolveClassByName(rawName) ?: resolveClassByName(name)
        }
    }

    private fun resolveClassByName(name: String): ObjClass? {
        val rec = seedScope?.get(name) ?: importManager.rootScope.get(name)
        (rec?.value as? ObjClass)?.let { return it }
        for (module in importedModules.asReversed()) {
            val imported = module.scope.get(name)
            (imported?.value as? ObjClass)?.let { return it }
        }
        val info = compileClassInfos[name] ?: return null
        val stub = compileClassStubs.getOrPut(info.name) {
            val parents = info.baseNames.mapNotNull { resolveClassByName(it) }
            val closedParent = parents.firstOrNull { it.isClosed }
            if (closedParent != null) {
                throw ScriptError(Pos.builtIn, "can't inherit from closed class ${closedParent.className}")
            }
            ObjInstanceClass(info.name, *parents.toTypedArray()).apply {
                logicalPackageNameOverride = info.packageName
            }
        }
        if (stub is ObjInstanceClass) {
            for ((fieldName, fieldId) in info.fieldIds) {
                if (stub.members[fieldName] == null) {
                    stub.createField(
                        fieldName,
                        ObjNull,
                        isMutable = true,
                        visibility = Visibility.Public,
                        pos = Pos.builtIn,
                        declaringClass = stub,
                        type = ObjRecord.Type.Field,
                        fieldId = fieldId
                    )
                }
            }
            for ((methodName, methodId) in info.methodIds) {
                if (stub.members[methodName] == null) {
                    stub.createField(
                        methodName,
                        ObjNull,
                        isMutable = false,
                        visibility = Visibility.Public,
                        pos = Pos.builtIn,
                        declaringClass = stub,
                        isAbstract = true,
                        type = ObjRecord.Type.Fun,
                        methodId = methodId
                    )
                }
            }
        }
        return stub
    }

    private suspend fun parseBlockWithPredeclared(
        predeclared: List<Pair<String, Boolean>>,
        skipLeadingBrace: Boolean = false,
        predeclaredTypes: Map<String, ObjClass> = emptyMap()
    ): Statement {
        val startPos = cc.currentPos()
        if (!skipLeadingBrace) {
            val t = cc.next()
            if (t.type != Token.Type.LBRACE)
                throw ScriptError(t.pos, "Expected block body start: {")
        }
        resolutionSink?.enterScope(ScopeKind.BLOCK, startPos, null)
        val blockSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        for ((name, isMutable) in predeclared) {
            declareSlotNameIn(blockSlotPlan, name, isMutable, isDelegated = false)
            resolutionSink?.declareSymbol(name, SymbolKind.LOCAL, isMutable, startPos, isOverride = false)
        }
        if (predeclaredTypes.isNotEmpty()) {
            val typeMap = slotTypeByScopeId.getOrPut(blockSlotPlan.id) { mutableMapOf() }
            for ((name, cls) in predeclaredTypes) {
                val slot = blockSlotPlan.slots[name]?.index ?: continue
                typeMap[slot] = cls
            }
        }
        slotPlanStack.add(blockSlotPlan)
        val capturePlan = CapturePlan(blockSlotPlan, isFunction = false, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val block = try {
            parseScript()
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        val planSnapshot = slotPlanIndices(blockSlotPlan)
        val stmt = BlockStatement(block, planSnapshot, blockSlotPlan.id, capturePlan.captures.toList(), startPos)
        val wrapped = wrapBytecode(stmt)
        return wrapped.also {
            val t1 = cc.next()
            if (t1.type != Token.Type.RBRACE)
                throw ScriptError(t1.pos, "unbalanced braces: expected block body end: }")
            // Record last parsed block range and notify Mini-AST sink
            val range = MiniRange(startPos, t1.pos)
            lastParsedBlockRange = range
            miniSink?.onBlock(MiniBlock(range))
            resolutionSink?.exitScope(t1.pos)
        }
    }

    private suspend fun parseLoopBlock(): Statement {
        val startPos = cc.currentPos()
        val t = cc.next()
        if (t.type != Token.Type.LBRACE)
            throw ScriptError(t.pos, "Expected block body start: {")
        resolutionSink?.enterScope(ScopeKind.BLOCK, startPos, null)
        val blockSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        slotPlanStack.add(blockSlotPlan)
        val capturePlan = CapturePlan(blockSlotPlan, isFunction = false, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val block = try {
            parseScript()
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        val planSnapshot = slotPlanIndices(blockSlotPlan)
        val stmt = BlockStatement(block, planSnapshot, blockSlotPlan.id, capturePlan.captures.toList(), startPos)
        val wrapped = wrapBytecode(stmt)
        return wrapped.also {
            val t1 = cc.next()
            if (t1.type != Token.Type.RBRACE)
                throw ScriptError(t1.pos, "unbalanced braces: expected block body end: }")
            val range = MiniRange(startPos, t1.pos)
            lastParsedBlockRange = range
            miniSink?.onBlock(MiniBlock(range))
            resolutionSink?.exitScope(t1.pos)
        }
    }

    private suspend fun parseLoopBlockWithPlan(): Pair<Statement, SlotPlan> {
        val startPos = cc.currentPos()
        val t = cc.next()
        if (t.type != Token.Type.LBRACE)
            throw ScriptError(t.pos, "Expected block body start: {")
        resolutionSink?.enterScope(ScopeKind.BLOCK, startPos, null)
        val blockSlotPlan = SlotPlan(mutableMapOf(), 0, nextScopeId++)
        slotPlanStack.add(blockSlotPlan)
        val capturePlan = CapturePlan(blockSlotPlan, isFunction = false, propagateToParentFunction = lambdaDepth > 0)
        capturePlanStack.add(capturePlan)
        val block = try {
            parseScript()
        } finally {
            capturePlanStack.removeLast()
            slotPlanStack.removeLast()
        }
        val planSnapshot = slotPlanIndices(blockSlotPlan)
        val stmt = BlockStatement(block, planSnapshot, blockSlotPlan.id, capturePlan.captures.toList(), startPos)
        val wrapped = wrapBytecode(stmt)
        val t1 = cc.next()
        if (t1.type != Token.Type.RBRACE)
            throw ScriptError(t1.pos, "unbalanced braces: expected block body end: }")
        val range = MiniRange(startPos, t1.pos)
        lastParsedBlockRange = range
        miniSink?.onBlock(MiniBlock(range))
        resolutionSink?.exitScope(t1.pos)
        return wrapped to blockSlotPlan
    }

    private suspend fun parseVarDeclaration(
        isMutable: Boolean,
        visibility: Visibility,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isStatic: Boolean = false,
        isExtern: Boolean = false,
        isTransient: Boolean = isTransientFlag
    ): Statement {
        isTransientFlag = false
        val actualExtern = isExtern || (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.isExtern == true
        val markStart = cc.savePos()
        val nextToken = cc.next()
        val start = nextToken.pos

        if (nextToken.type == Token.Type.LBRACKET) {
            // Destructuring
            if (isStatic) throw ScriptError(start, "static destructuring is not supported")

            val entries = parseDestructuringPattern()
            val pattern = ListLiteralRef(entries)

            // Register all names in the pattern
            pattern.forEachVariableWithPos { name, namePos ->
                declareLocalName(name, isMutable)
                val declKind = if (codeContexts.lastOrNull() is CodeContext.ClassBody) {
                    SymbolKind.MEMBER
                } else {
                    SymbolKind.LOCAL
                }
                resolutionSink?.declareSymbol(name, declKind, isMutable, namePos, isOverride = false)
                val declRange = MiniRange(namePos, namePos)
                val node = MiniValDecl(
                    range = declRange,
                    name = name,
                    mutable = isMutable,
                    type = null,
                    initRange = null,
                    doc = pendingDeclDoc,
                    nameStart = namePos,
                    isExtern = actualExtern,
                    isStatic = false
                )
                miniSink?.onValDecl(node)
            }
            pendingDeclDoc = null

            val eqToken = cc.next()
            if (eqToken.type != Token.Type.ASSIGN)
                throw ScriptError(eqToken.pos, "destructuring declaration must be initialized")

            val initialExpression = parseStatement(true)
                ?: throw ScriptError(eqToken.pos, "Expected initializer expression")

            val names = mutableListOf<String>()
            pattern.forEachVariable { names.add(it) }

            return DestructuringVarDeclStatement(
                pattern,
                names,
                initialExpression,
                isMutable,
                visibility,
                isTransient,
                start
            )
        }

        cc.restorePos(markStart)
        var name: String
        var extTypeName: String? = null
        var nameStartPos: Pos
        var receiverMini: MiniTypeRef? = null
        var receiverTypeDecl: TypeDecl? = null

        if (looksLikeExtensionReceiver()) {
            val (recvDecl, recvMini) = parseExtensionReceiverTypeWithMini()
            receiverTypeDecl = recvDecl
            receiverMini = recvMini
            val dot = cc.nextNonWhitespace()
            if (dot.type != Token.Type.DOT)
                throw ScriptError(dot.pos, "Expected '.' after extension receiver type")
            val nameToken = cc.next()
            if (nameToken.type != Token.Type.ID)
                throw ScriptError(nameToken.pos, "Expected identifier after dot in extension declaration")
            name = nameToken.value
            nameStartPos = nameToken.pos
            extTypeName = when (recvDecl) {
                is TypeDecl.Simple -> recvDecl.name.substringAfterLast('.')
                is TypeDecl.Generic -> recvDecl.name.substringAfterLast('.')
                else -> throw ScriptError(
                    recvMini.range.start,
                    "illegal extension receiver type: ${typeDeclName(recvDecl)}"
                )
            }
            registerExtensionName(extTypeName, name)
        } else {
            val nameToken = cc.next()
            if (nameToken.type != Token.Type.ID)
                throw ScriptError(nameToken.pos, "Expected identifier or [ here")
            name = nameToken.value
            nameStartPos = nameToken.pos
        }
        val receiverNormalization = normalizeReceiverTypeDecl(receiverTypeDecl, emptySet())
        val implicitTypeParams = receiverNormalization.second
        if (implicitTypeParams.isNotEmpty()) pendingTypeParamStack.add(implicitTypeParams)
        try {

        val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
        var memberFieldId = if (extTypeName == null) classCtx?.memberFieldIds?.get(name) else null
        var memberMethodId = if (extTypeName == null) classCtx?.memberMethodIds?.get(name) else null

        // Optional explicit type annotation
        cc.skipWsTokens()
        var (varTypeDecl, varTypeMini) = if (cc.peekNextNonWhitespace().type == Token.Type.COLON) {
            parseTypeDeclarationWithMini()
        } else {
            TypeDecl.TypeAny to null
        }

        val markBeforeEq = cc.savePos()
        cc.skipWsTokens()
        val eqToken = cc.next()
        var setNull = false
        var isProperty = false

        val declaringClassNameCaptured = (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.name

        if (declaringClassNameCaptured != null || extTypeName != null) {
            val mark = cc.savePos()
            cc.restorePos(markBeforeEq)
            cc.skipWsTokens()
            
            // Heuristic: if we see 'get(' or 'set(' or 'private set(' or 'protected set(', 
            // look ahead for a body.
            fun hasAccessorWithBody(): Boolean {
                val t = cc.peekNextNonWhitespace()
                if (t.isId("get") || t.isId("set")) {
                    val saved = cc.savePos()
                    cc.next() // consume get/set
                    val nextToken = cc.peekNextNonWhitespace()
                    if (nextToken.type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        var depth = 1
                        while (cc.hasNext() && depth > 0) {
                            val tt = cc.next()
                            if (tt.type == Token.Type.LPAREN) depth++
                            else if (tt.type == Token.Type.RPAREN) depth--
                        }
                        val next = cc.peekNextNonWhitespace()
                        if (next.type == Token.Type.LBRACE || next.type == Token.Type.ASSIGN) {
                            cc.restorePos(saved)
                            return true
                        }
                    } else if (nextToken.type == Token.Type.LBRACE || nextToken.type == Token.Type.ASSIGN) {
                        cc.restorePos(saved)
                        return true
                    }
                    cc.restorePos(saved)
                } else if (t.isId("private") || t.isId("protected")) {
                    val saved = cc.savePos()
                    cc.next() // consume modifier
                    if (cc.skipWsTokens().isId("set")) {
                        cc.next() // consume set
                        val nextToken = cc.peekNextNonWhitespace()
                        if (nextToken.type == Token.Type.LPAREN) {
                            cc.next() // consume (
                            var depth = 1
                            while (cc.hasNext() && depth > 0) {
                                val tt = cc.next()
                                if (tt.type == Token.Type.LPAREN) depth++
                                else if (tt.type == Token.Type.RPAREN) depth--
                            }
                            val next = cc.peekNextNonWhitespace()
                            if (next.type == Token.Type.LBRACE || next.type == Token.Type.ASSIGN) {
                                cc.restorePos(saved)
                                return true
                            }
                        } else if (nextToken.type == Token.Type.LBRACE || nextToken.type == Token.Type.ASSIGN) {
                            cc.restorePos(saved)
                            return true
                        }
                    }
                    cc.restorePos(saved)
                }
                return false
            }
            
            if (hasAccessorWithBody()) {
                isProperty = true
                cc.restorePos(markBeforeEq)
                // Do not consume eqToken if it's an accessor keyword
            } else {
                cc.restorePos(mark)
            }
        }

        val effectiveEqToken = if (isProperty) null else eqToken

        // Register the local name at compile time so that subsequent identifiers can be emitted as fast locals
        val isTopLevelExtern = actualExtern && declaringClassNameCaptured == null && slotPlanStack.size == 1
        if (!isStatic && declaringClassNameCaptured == null && (!actualExtern || isTopLevelExtern)) {
            declareLocalName(name, isMutable)
        }
        val declKind = if (codeContexts.lastOrNull() is CodeContext.ClassBody) {
            SymbolKind.MEMBER
        } else {
            SymbolKind.LOCAL
        }
        resolutionSink?.declareSymbol(name, declKind, isMutable, nameStartPos, isOverride = isOverride)
        if (declKind == SymbolKind.MEMBER && extTypeName == null) {
            (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.declaredMembers?.add(name)
        } else if (actualExtern) {
            externBindingNames.add(name)
        }

        val isDelegate = if (isAbstract || actualExtern) {
            if (!isProperty && (effectiveEqToken?.type == Token.Type.ASSIGN || effectiveEqToken?.type == Token.Type.BY))
                throw ScriptError(effectiveEqToken.pos, "${if (isAbstract) "abstract" else "extern"} variable $name cannot have an initializer or delegate")
            // Abstract or extern variables don't have initializers
            cc.restorePos(markBeforeEq)
            cc.skipWsTokens()
            setNull = true
            false
        } else if (!isProperty && effectiveEqToken?.type == Token.Type.BY) {
            true
        } else {
            if (!isProperty && effectiveEqToken?.type != Token.Type.ASSIGN) {
                if (!isMutable && (declaringClassNameCaptured == null) && (extTypeName == null))
                    throw ScriptError(start, "val must be initialized")
                else if (!isMutable && declaringClassNameCaptured != null && extTypeName == null) {
                    // lateinit val in class: track it
                    (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.pendingInitializations?.put(name, start)
                    cc.restorePos(markBeforeEq)
                    cc.skipWsTokens()
                    setNull = true
                } else {
                    cc.restorePos(markBeforeEq)
                    cc.skipWsTokens()
                    setNull = true
                }
            }
            false
        }

        if (declaringClassNameCaptured != null && extTypeName == null) {
            if (isStatic) {
                classCtx?.classScopeMembers?.add(name)
                registerClassScopeMember(declaringClassNameCaptured, name)
            } else if (isDelegate || isProperty) {
                if (classCtx != null) {
                    if (!classCtx.memberMethodIds.containsKey(name)) {
                        classCtx.memberMethodIds[name] = classCtx.nextMethodId++
                    }
                    memberMethodId = classCtx.memberMethodIds[name]
                }
            } else {
                if (classCtx != null) {
                    if (!classCtx.memberFieldIds.containsKey(name)) {
                        classCtx.memberFieldIds[name] = classCtx.nextFieldId++
                    }
                    memberFieldId = classCtx.memberFieldIds[name]
                }
            }
        }

        val initialExpression = if (setNull || isProperty) null
        else parseStatement(true)
            ?: throw ScriptError(effectiveEqToken!!.pos, "Expected initializer expression")

        if (varTypeDecl == TypeDecl.TypeAny && initialExpression != null) {
            val inferred = inferTypeDeclFromInitializer(initialExpression)
            if (inferred != null) {
                varTypeDecl = inferred
            }
        }
        if (isDelegate && initialExpression != null) {
            ensureDelegateType(initialExpression)
            val lazyClass = resolveClassByName("lazy")
            if (isMutable &&
                lazyClass != null &&
                resolveInitializerObjClass(initialExpression)?.logicalName == lazyClass.logicalName
            ) {
                throw ScriptError(initialExpression.pos, "lazy delegate is read-only")
            }
        }

        if (!isStatic && isDelegate) {
            markDelegatedSlot(name)
        }

        if (declaringClassNameCaptured != null && extTypeName == null && !isStatic) {
            val directRef = unwrapDirectRef(initialExpression)
            val declClass = resolveTypeDeclObjClass(varTypeDecl)
            val initFromExpr = resolveInitializerObjClass(initialExpression)
            val isNullLiteral = (directRef as? ConstRef)?.constValue == ObjNull
            val initClass = when {
                isDelegate && declClass != null -> declClass
                declClass != null && isNullLiteral -> declClass
                else -> initFromExpr ?: declClass
            }
            if (initClass != null) {
                classFieldTypesByName.getOrPut(declaringClassNameCaptured) { mutableMapOf() }[name] = initClass
            }
        }

        // Emit MiniValDecl for this declaration (before execution wiring), attach doc if any
        run {
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val initR = if (setNull || isProperty) null else MiniRange(effectiveEqToken!!.pos, cc.currentPos())
            val node = MiniValDecl(
                range = declRange,
                name = name,
                mutable = isMutable,
                type = varTypeMini,
                initRange = initR,
                doc = pendingDeclDoc,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onValDecl(node)
            pendingDeclDoc = null
        }

        fun buildVarDecl(initialExpression: Statement?): VarDeclStatement {
            val slotPlan = slotPlanStack.lastOrNull()
            val slotIndex = slotPlan?.slots?.get(name)?.index
            val scopeId = slotPlan?.id
            val directRef = unwrapDirectRef(initialExpression)
            val declClass = resolveTypeDeclObjClass(varTypeDecl)
            val initFromExpr = resolveInitializerObjClass(initialExpression)
            val isNullLiteral = (directRef as? ConstRef)?.constValue == ObjNull
            val initObjClass = if (declClass != null && isNullLiteral) declClass else initFromExpr ?: declClass
            if (varTypeDecl !is TypeDecl.TypeAny && varTypeDecl !is TypeDecl.TypeNullableAny) {
                if (slotIndex != null && scopeId != null) {
                    slotTypeDeclByScopeId.getOrPut(scopeId) { mutableMapOf() }[slotIndex] = varTypeDecl
                }
                nameTypeDecl[name] = varTypeDecl
            }
            if (directRef is ValueFnRef) {
                val returnClass = lambdaReturnTypeByRef[directRef]
                if (returnClass != null) {
                    if (slotIndex != null && scopeId != null) {
                        callableReturnTypeByScopeId.getOrPut(scopeId) { mutableMapOf() }[slotIndex] = returnClass
                    }
                    callableReturnTypeByName[name] = returnClass
                }
            }
            if (directRef is MethodCallRef && directRef.name == "encode") {
                val payloadClass = inferEncodedPayloadClass(directRef.args)
                if (payloadClass != null) {
                    if (slotIndex != null && scopeId != null) {
                        encodedPayloadTypeByScopeId.getOrPut(scopeId) { mutableMapOf() }[slotIndex] = payloadClass
                    }
                    encodedPayloadTypeByName[name] = payloadClass
                }
            }
            if (initObjClass != null) {
                if (slotIndex != null && scopeId != null) {
                    slotTypeByScopeId.getOrPut(scopeId) { mutableMapOf() }[slotIndex] = initObjClass
                }
                nameObjClass[name] = initObjClass
            }
            val declaredType = if (varTypeDecl == TypeDecl.TypeAny || varTypeDecl == TypeDecl.TypeNullableAny) {
                null
            } else {
                varTypeDecl
            }
            return VarDeclStatement(
                name,
                isMutable,
                visibility,
                actualExtern,
                initialExpression,
                isTransient,
                declaredType,
                slotIndex,
                scopeId,
                start,
                initObjClass
            )
        }

        if (declaringClassNameCaptured == null &&
            extTypeName == null &&
            !isStatic &&
            !isProperty
        ) {
            if (isDelegate) {
                val initExpr = initialExpression ?: throw ScriptError(start, "Delegate must be initialized")
                val slotPlan = slotPlanStack.lastOrNull()
                val slotIndex = slotPlan?.slots?.get(name)?.index
                val scopeId = slotPlan?.id
                return DelegatedVarDeclStatement(
                    name,
                    isMutable,
                    visibility,
                    initExpr,
                    isTransient,
                    slotIndex,
                    scopeId,
                    start
                )
            }
            return buildVarDecl(initialExpression)
        }

        if (isStatic) {
            if (declaringClassNameCaptured != null) {
                val directRef = unwrapDirectRef(initialExpression)
                val declClass = resolveTypeDeclObjClass(varTypeDecl)
                val initFromExpr = resolveInitializerObjClass(initialExpression)
                val isNullLiteral = (directRef as? ConstRef)?.constValue == ObjNull
                val initClass = if (declClass != null && isNullLiteral) declClass else initFromExpr ?: declClass
                if (initClass != null) {
                    classFieldTypesByName.getOrPut(declaringClassNameCaptured) { mutableMapOf() }[name] = initClass
                }
            }
            // find objclass instance: this is tricky: this code executes in object initializer,
            // when creating instance, but we need to execute it in the class initializer which
            // is missing as for now. Add it to the compiler context?

            currentInitScope += ClassStaticFieldInitStatement(
                name = name,
                isMutable = isMutable,
                visibility = visibility,
                writeVisibility = null,
                initializer = initialExpression,
                isDelegated = isDelegate,
                isTransient = isTransient,
                startPos = start
            )
            return NopStatement
        }

        // Check for accessors if it is a class member
        var getter: Obj? = null
        var setter: Obj? = null
        var setterVisibility: Visibility? = null
        if (declaringClassNameCaptured != null || extTypeName != null) {
            val classCtx = codeContexts.asReversed().firstOrNull { it is CodeContext.ClassBody } as? CodeContext.ClassBody
            val accessorImplicitThisMembers = extTypeName != null || (classCtx != null && !isStatic)
            val accessorImplicitThisTypeName = extTypeName ?: if (classCtx != null && !isStatic) classCtx.name else null
            while (true) {
                val t = cc.skipWsTokens()
                if (t.isId("get")) {
                    val getStart = cc.currentPos()
                    cc.next() // consume 'get'
                    if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        cc.requireToken(Token.Type.RPAREN)
                    }
                    miniSink?.onEnterFunction(null)
                    getter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        cc.skipWsTokens()
                        inCodeContext(
                            CodeContext.Function(
                                "<getter>",
                                implicitThisMembers = accessorImplicitThisMembers,
                                implicitThisTypeName = accessorImplicitThisTypeName
                            )
                        ) {
                            wrapFunctionBytecode(parseBlock(), "<getter>")
                        }
                    } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                        cc.skipWsTokens()
                        cc.next() // consume '='
                        inCodeContext(
                            CodeContext.Function(
                                "<getter>",
                                implicitThisMembers = accessorImplicitThisMembers,
                                implicitThisTypeName = accessorImplicitThisTypeName
                            )
                        ) {
                            val expr = parseExpression()
                                ?: throw ScriptError(cc.current().pos, "Expected getter expression")
                            wrapFunctionBytecode(expr, "<getter>")
                        }
                    } else {
                        throw ScriptError(cc.current().pos, "Expected { or = after get()")
                    }
                    miniSink?.onExitFunction(cc.currentPos())
                } else if (t.isId("set")) {
                    val setStart = cc.currentPos()
                    cc.next() // consume 'set'
                    var setArgName = "it"
                    if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        setArgName = cc.requireToken(Token.Type.ID, "Expected setter argument name").value
                        cc.requireToken(Token.Type.RPAREN)
                    }
                    miniSink?.onEnterFunction(null)
                    setter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        cc.skipWsTokens()
                        val body = inCodeContext(
                            CodeContext.Function(
                                "<setter>",
                                implicitThisMembers = accessorImplicitThisMembers,
                                implicitThisTypeName = accessorImplicitThisTypeName
                            )
                        ) {
                            wrapFunctionBytecode(parseBlockWithPredeclared(listOf(setArgName to true)), "<setter>")
                        }
                        PropertyAccessorStatement(body, setArgName, body.pos)
                    } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                        cc.skipWsTokens()
                        cc.next() // consume '='
                        val expr = inCodeContext(
                            CodeContext.Function(
                                "<setter>",
                                implicitThisMembers = accessorImplicitThisMembers,
                                implicitThisTypeName = accessorImplicitThisTypeName
                            )
                        ) {
                            parseExpressionBlockWithPredeclared(listOf(setArgName to true))
                        }
                        val st = wrapFunctionBytecode(expr, "<setter>")
                        PropertyAccessorStatement(st, setArgName, st.pos)
                    } else {
                        throw ScriptError(cc.current().pos, "Expected { or = after set(...)")
                    }
                    miniSink?.onExitFunction(cc.currentPos())
                } else if (t.isId("private") || t.isId("protected")) {
                    val vis = if (t.isId("private")) Visibility.Private else Visibility.Protected
                    val mark = cc.savePos()
                    cc.next() // consume modifier
                    if (cc.skipWsTokens().isId("set")) {
                        cc.next() // consume 'set'
                        setterVisibility = vis
                        if (cc.skipWsTokens().type == Token.Type.LPAREN) {
                            cc.next() // consume '('
                            val setArg = cc.requireToken(Token.Type.ID, "Expected setter argument name")
                            cc.requireToken(Token.Type.RPAREN)
                            miniSink?.onEnterFunction(null)
                            val finalSetter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                                cc.skipWsTokens()
                                val body = inCodeContext(
                                    CodeContext.Function(
                                        "<setter>",
                                        implicitThisMembers = accessorImplicitThisMembers,
                                        implicitThisTypeName = accessorImplicitThisTypeName
                                    )
                                ) {
                                    parseBlockWithPredeclared(listOf(setArg.value to true))
                                }
                                val wrapped = wrapFunctionBytecode(body, "<setter>")
                                PropertyAccessorStatement(wrapped, setArg.value, body.pos)
                            } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                                cc.skipWsTokens()
                                cc.next() // consume '='
                                val st = inCodeContext(
                                    CodeContext.Function(
                                        "<setter>",
                                        implicitThisMembers = accessorImplicitThisMembers,
                                        implicitThisTypeName = accessorImplicitThisTypeName
                                    )
                                ) {
                                    parseExpressionBlockWithPredeclared(listOf(setArg.value to true))
                                }
                                val wrapped = wrapFunctionBytecode(st, "<setter>")
                                PropertyAccessorStatement(wrapped, setArg.value, st.pos)
                            } else {
                                throw ScriptError(cc.current().pos, "Expected { or = after set(...)")
                            }
                            setter = finalSetter
                            miniSink?.onExitFunction(cc.currentPos())
                        } else {
                            // private set without body: setter remains null, visibility is restricted
                        }
                    } else {
                        cc.restorePos(mark)
                        break
                    }
                } else break
            }
            if (getter != null || setter != null) {
                if (isMutable) {
                    if (getter == null || setter == null) {
                        throw ScriptError(start, "var property must have both get() and set()")
                    }
                } else {
                    if (setter != null || setterVisibility != null)
                        throw ScriptError(start, "val property cannot have a setter or restricted visibility set (name: $name)")
                    if (getter == null)
                        throw ScriptError(start, "val property with accessors must have a getter (name: $name)")
                }
            } else if (setterVisibility != null && !isMutable) {
                throw ScriptError(start, "val field cannot have restricted visibility set (name: $name)")
            }
        }

        if (extTypeName != null) {
            declareLocalName(extensionPropertyGetterName(extTypeName, name), isMutable = false)
            if (setter != null) {
                declareLocalName(extensionPropertySetterName(extTypeName, name), isMutable = false)
            }
            val prop = if (getter != null || setter != null) {
                ObjProperty(name, getter, setter)
            } else {
                // Simple val extension with initializer
                val initExpr = initialExpression ?: throw ScriptError(start, "Extension val must be initialized")
                ObjProperty(name, initExpr, null)
            }
            return ExtensionPropertyDeclStatement(
                extTypeName = extTypeName,
                property = prop,
                visibility = visibility,
                setterVisibility = setterVisibility,
                startPos = start
            )
        }

        if (declaringClassNameCaptured != null) {
            val storageName = "$declaringClassNameCaptured::$name"
            if (isDelegate) {
                val initExpr = initialExpression ?: throw ScriptError(start, "Delegate must be initialized")
                val initStmt = if (!isAbstract) {
                    val accessType = if (isMutable) "Var" else "Val"
                    val initStatement = InstanceDelegatedInitStatement(
                        storageName = storageName,
                        memberName = name,
                        isMutable = isMutable,
                        visibility = visibility,
                        writeVisibility = setterVisibility,
                        isAbstract = isAbstract,
                        isClosed = isClosed,
                        isOverride = isOverride,
                        isTransient = isTransient,
                        accessTypeLabel = accessType,
                        initializer = initExpr,
                        pos = start
                    )
                    wrapInstanceInitBytecode(initStatement, "delegated@${storageName}")
                } else null
                return ClassInstanceDelegatedDeclStatement(
                    name = name,
                    isMutable = isMutable,
                    visibility = visibility,
                    writeVisibility = setterVisibility,
                    isAbstract = isAbstract,
                    isClosed = isClosed,
                    isOverride = isOverride,
                    isTransient = isTransient,
                    methodId = memberMethodId,
                    initStatement = initStmt,
                    pos = start
                )
            }
            if (getter != null || setter != null) {
                val prop = ObjProperty(name, getter, setter)
                val initStmt = if (!isAbstract) {
                    val initStatement = InstancePropertyInitStatement(
                        storageName = storageName,
                        isMutable = isMutable,
                        visibility = visibility,
                        writeVisibility = setterVisibility,
                        isAbstract = isAbstract,
                        isClosed = isClosed,
                        isOverride = isOverride,
                        isTransient = isTransient,
                        prop = prop,
                        pos = start
                    )
                    wrapInstanceInitBytecode(initStatement, "property@${storageName}")
                } else null
                return ClassInstancePropertyDeclStatement(
                    name = name,
                    isMutable = isMutable,
                    visibility = visibility,
                    writeVisibility = setterVisibility,
                    isAbstract = isAbstract,
                    isClosed = isClosed,
                    isOverride = isOverride,
                    isTransient = isTransient,
                    prop = prop,
                    methodId = memberMethodId,
                    initStatement = initStmt,
                    pos = start
                )
            }
            val isLateInitVal = !isMutable && initialExpression == null
            val initStmt = if (!isAbstract) {
                val initStatement = InstanceFieldInitStatement(
                    storageName = storageName,
                    isMutable = isMutable,
                    visibility = visibility,
                    writeVisibility = setterVisibility,
                    isAbstract = isAbstract,
                    isClosed = isClosed,
                    isOverride = isOverride,
                    isTransient = isTransient,
                    isLateInitVal = isLateInitVal,
                    initializer = initialExpression,
                    pos = start
                )
                wrapInstanceInitBytecode(initStatement, "field@${storageName}")
            } else null
            return ClassInstanceFieldDeclStatement(
                name = name,
                isMutable = isMutable,
                visibility = visibility,
                writeVisibility = setterVisibility,
                typeDecl = if (varTypeDecl == TypeDecl.TypeAny || varTypeDecl == TypeDecl.TypeNullableAny) null else varTypeDecl,
                isAbstract = isAbstract,
                isClosed = isClosed,
                isOverride = isOverride,
                isTransient = isTransient,
                fieldId = memberFieldId,
                initStatement = initStmt,
                pos = start
            )
        }

        return buildVarDecl(initialExpression)
    } finally {
        if (implicitTypeParams.isNotEmpty()) pendingTypeParamStack.removeLast()
    }
}

    data class Operator(
        val tokenType: Token.Type,
        val priority: Int, val arity: Int = 2,
        val generate: (Pos, ObjRef, ObjRef) -> ObjRef
    ) {
//        fun isLeftAssociative() = tokenType != Token.Type.OR && tokenType != Token.Type.AND

        companion object

    }

    companion object {

        suspend fun compile(source: Source, importManager: ImportProvider): Script {
            val script = Compiler(CompilerContext(parseLyng(source)), importManager).parseScript()
            return script
        }

        suspend fun dryRun(source: Source, importManager: ImportProvider): ResolutionReport {
            return CompileTimeResolver.dryRun(source, importManager)
        }

        /**
         * Compile [source] while streaming a Mini-AST into the provided [sink].
         * When [sink] is null, behaves like [compile].
         */
        suspend fun compileWithMini(
            source: Source,
            importManager: ImportProvider,
            sink: MiniAstSink?
        ): Script {
            return compileWithResolution(source, importManager, sink, null)
        }

        /** Convenience overload to compile raw [code] with a Mini-AST [sink]. */
        suspend fun compileWithMini(code: String, sink: MiniAstSink?): Script =
            compileWithMini(Source("<eval>", code), Script.defaultImportManager, sink)

        suspend fun compileWithResolution(
            source: Source,
            importManager: ImportProvider,
            miniSink: MiniAstSink? = null,
            resolutionSink: ResolutionSink? = null,
            compileBytecode: Boolean = true,
            strictSlotRefs: Boolean = true,
            allowUnresolvedRefs: Boolean = false,
            seedScope: Scope? = null,
            useFastLocalRefs: Boolean = false
        ): Script {
            return Compiler(
                CompilerContext(parseLyng(source)),
                importManager,
                Settings(
                    miniAstSink = miniSink,
                    resolutionSink = resolutionSink,
                    compileBytecode = compileBytecode,
                    strictSlotRefs = strictSlotRefs,
                    allowUnresolvedRefs = allowUnresolvedRefs,
                    seedScope = seedScope,
                    useFastLocalRefs = useFastLocalRefs
                )
            ).parseScript()
        }

        private var lastPriority = 0

        // Helpers for conservative constant folding (literal-only). Only pure, side-effect-free ops.
        private fun constOf(r: ObjRef): Obj? = (r as? ConstRef)?.constValue

        private fun foldBinary(op: BinOp, aRef: ObjRef, bRef: ObjRef): Obj? {
            val a = constOf(aRef) ?: return null
            val b = constOf(bRef) ?: return null
            return when (op) {
                // Boolean logic
                BinOp.OR -> if (a is ObjBool && b is ObjBool) if (a.value || b.value) ObjTrue else ObjFalse else null
                BinOp.AND -> if (a is ObjBool && b is ObjBool) if (a.value && b.value) ObjTrue else ObjFalse else null

                // Equality and comparisons for ints/strings/chars
                BinOp.EQ -> when {
                    a is ObjInt && b is ObjInt -> if (a.value == b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value == b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value == b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.NEQ -> when {
                    a is ObjInt && b is ObjInt -> if (a.value != b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value != b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value != b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.LT -> when {
                    a is ObjInt && b is ObjInt -> if (a.value < b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value < b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value < b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.LTE -> when {
                    a is ObjInt && b is ObjInt -> if (a.value <= b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value <= b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value <= b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.GT -> when {
                    a is ObjInt && b is ObjInt -> if (a.value > b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value > b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value > b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.GTE -> when {
                    a is ObjInt && b is ObjInt -> if (a.value >= b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value >= b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value >= b.value) ObjTrue else ObjFalse
                    else -> null
                }

                // Arithmetic for ints only (keep semantics simple at compile time)
                BinOp.PLUS -> when {
                    a is ObjInt && b is ObjInt -> ObjInt.of(a.value + b.value)
                    a is ObjString && b is ObjString -> ObjString(a.value + b.value)
                    else -> null
                }

                BinOp.MINUS -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value - b.value) else null
                BinOp.STAR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value * b.value) else null
                BinOp.SLASH -> if (a is ObjInt && b is ObjInt && b.value != 0L) ObjInt.of(a.value / b.value) else null
                BinOp.PERCENT -> if (a is ObjInt && b is ObjInt && b.value != 0L) ObjInt.of(a.value % b.value) else null

                // Bitwise for ints
                BinOp.BAND -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value and b.value) else null
                BinOp.BXOR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value xor b.value) else null
                BinOp.BOR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value or b.value) else null
                BinOp.SHL -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value shl (b.value.toInt() and 63)) else null
                BinOp.SHR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value shr (b.value.toInt() and 63)) else null

                // Non-folded / side-effecting or type-dependent ops
                BinOp.EQARROW, BinOp.REF_EQ, BinOp.REF_NEQ, BinOp.MATCH, BinOp.NOTMATCH,
                BinOp.IN, BinOp.NOTIN, BinOp.IS, BinOp.NOTIS, BinOp.SHUTTLE -> null
            }
        }

        private fun foldUnary(op: UnaryOp, aRef: ObjRef): Obj? {
            val a = constOf(aRef) ?: return null
            return when (op) {
                UnaryOp.NOT -> if (a is ObjBool) if (!a.value) ObjTrue else ObjFalse else null
                UnaryOp.NEGATE -> when (a) {
                    is ObjInt -> ObjInt.of(-a.value)
                    is ObjReal -> ObjReal.of(-a.value)
                    else -> null
                }

                UnaryOp.BITNOT -> if (a is ObjInt) ObjInt.of(a.value.inv()) else null
            }
        }

        val allOps = listOf(
            // assignments, lowest priority
            Operator(Token.Type.ASSIGN, lastPriority) { pos, a, b ->
                AssignRef(a, b, pos)
            },
            Operator(Token.Type.PLUSASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.PLUS, a, b, pos)
            },
            Operator(Token.Type.MINUSASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.MINUS, a, b, pos)
            },
            Operator(Token.Type.STARASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.STAR, a, b, pos)
            },
            Operator(Token.Type.SLASHASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.SLASH, a, b, pos)
            },
            Operator(Token.Type.PERCENTASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.PERCENT, a, b, pos)
            },
            Operator(Token.Type.IFNULLASSIGN, lastPriority) { pos, a, b ->
                AssignIfNullRef(a, b, pos)
            },
            // logical 1
            Operator(Token.Type.OR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.OR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                LogicalOrRef(a, b)
            },
            // logical 2
            Operator(Token.Type.AND, ++lastPriority) { _, a, b ->
                LogicalAndRef(a, b)
            },
            // bitwise or/xor/and (tighter than &&, looser than equality)
            Operator(Token.Type.BITOR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BOR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BOR, a, b)
            },
            Operator(Token.Type.BITXOR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BXOR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BXOR, a, b)
            },
            Operator(Token.Type.BITAND, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BAND, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BAND, a, b)
            },
            // equality/not equality and related
            Operator(Token.Type.EQARROW, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.EQARROW, a, b)
            },
            Operator(Token.Type.EQ, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.EQ, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.EQ, a, b)
            },
            Operator(Token.Type.NEQ, lastPriority) { _, a, b ->
                foldBinary(BinOp.NEQ, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.NEQ, a, b)
            },
            Operator(Token.Type.REF_EQ, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.REF_EQ, a, b)
            },
            Operator(Token.Type.REF_NEQ, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.REF_NEQ, a, b)
            },
            Operator(Token.Type.MATCH, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.MATCH, a, b)
            },
            Operator(Token.Type.NOTMATCH, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTMATCH, a, b)
            },
            // relational <=,...
            Operator(Token.Type.LTE, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.LTE, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.LTE, a, b)
            },
            Operator(Token.Type.LT, lastPriority) { _, a, b ->
                foldBinary(BinOp.LT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.LT, a, b)
            },
            Operator(Token.Type.GTE, lastPriority) { _, a, b ->
                foldBinary(BinOp.GTE, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.GTE, a, b)
            },
            Operator(Token.Type.GT, lastPriority) { _, a, b ->
                foldBinary(BinOp.GT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.GT, a, b)
            },
            // in, is:
            Operator(Token.Type.IN, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.IN, a, b)
            },
            Operator(Token.Type.NOTIN, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTIN, a, b)
            },
            Operator(Token.Type.IS, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.IS, a, b)
            },
            Operator(Token.Type.NOTIS, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTIS, a, b)
            },
            // casts: as / as?
            Operator(Token.Type.AS, lastPriority) { pos, a, b ->
                CastRef(a, b, false, pos)
            },
            Operator(Token.Type.ASNULL, lastPriority) { pos, a, b ->
                CastRef(a, b, true, pos)
            },

            Operator(Token.Type.ELVIS, ++lastPriority, 2) { _, a, b ->
                ElvisRef(a, b)
            },

            // shuttle <=>
            Operator(Token.Type.SHUTTLE, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.SHUTTLE, a, b)
            },
            // shifts (tighter than shuttle, looser than +/-)
            Operator(Token.Type.SHL, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.SHL, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SHL, a, b)
            },
            Operator(Token.Type.SHR, lastPriority) { _, a, b ->
                foldBinary(BinOp.SHR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SHR, a, b)
            },
            // arithmetic
            Operator(Token.Type.PLUS, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.PLUS, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.PLUS, a, b)
            },
            Operator(Token.Type.MINUS, lastPriority) { _, a, b ->
                foldBinary(BinOp.MINUS, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.MINUS, a, b)
            },
            Operator(Token.Type.STAR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.STAR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.STAR, a, b)
            },
            Operator(Token.Type.SLASH, lastPriority) { _, a, b ->
                foldBinary(BinOp.SLASH, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SLASH, a, b)
            },
            Operator(Token.Type.PERCENT, lastPriority) { _, a, b ->
                foldBinary(BinOp.PERCENT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.PERCENT, a, b)
            },
        )

//        private val assigner = allOps.first { it.tokenType == Token.Type.ASSIGN }
//
//        fun performAssignment(context: Context, left: Accessor, right: Accessor) {
//            assigner.generate(context.pos, left, right)
//        }

        // Compute levels from the actual operator table rather than relying on
        // the mutable construction counter. This prevents accidental inflation
        // of precedence depth that could lead to deep recursive descent and
        // StackOverflowError during parsing.
        val lastLevel = (allOps.maxOf { it.priority }) + 1

        val byLevel: List<Map<Token.Type, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }.associateBy { it.tokenType }
        }

        suspend fun compile(code: String): Script = compile(Source("<eval>", code), Script.defaultImportManager)

        /**
         * The keywords that stop processing of expression term
         */
        val stopKeywords =
            setOf(
                "break", "continue", "return", "if", "when", "do", "while", "for", "class",
                "private", "protected", "val", "var", "fun", "fn", "static", "init", "enum"
            )
    }
}

suspend fun eval(code: String) = compile(code).execute()
suspend fun evalNamed(name: String, code: String, importManager: ImportManager = Script.defaultImportManager) =
    compile(Source(name,code), importManager).execute()
