/*
 * Copyright 2026 Sergey S. Chernov
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
 */

package net.sergeych.lyng

import net.sergeych.lyng.obj.Obj
import net.sergeych.lyng.obj.ObjClass
import net.sergeych.lyng.obj.ObjException
import net.sergeych.lyng.obj.ObjInstance
import net.sergeych.lyng.obj.ObjInstanceClass
import net.sergeych.lyng.obj.ObjNull
import net.sergeych.lyng.obj.ObjRecord

data class ClassDeclBaseSpec(
    val name: String,
    val args: List<ParsedArgument>?
)

data class ClassDeclSpec(
    val declaredName: String?,
    val className: String,
    val typeName: String,
    val startPos: Pos,
    val isExtern: Boolean,
    val isAbstract: Boolean,
    val isObject: Boolean,
    val isAnonymous: Boolean,
    val baseSpecs: List<ClassDeclBaseSpec>,
    val constructorArgs: ArgsDeclaration?,
    val constructorFieldIds: Map<String, Int>?,
    val bodyInit: Statement?,
    val initScope: List<Statement>,
)

internal suspend fun executeClassDecl(
    scope: Scope,
    spec: ClassDeclSpec,
    bodyCaptureRecords: List<ObjRecord>? = null,
    bodyCaptureNames: List<String>? = null
): Obj {
    if (spec.isObject) {
        val parentClasses = spec.baseSpecs.map { baseSpec ->
            val rec = scope[baseSpec.name] ?: throw ScriptError(spec.startPos, "unknown base class: ${baseSpec.name}")
            (rec.value as? ObjClass) ?: throw ScriptError(spec.startPos, "${baseSpec.name} is not a class")
        }

        val newClass = ObjInstanceClass(spec.className, *parentClasses.toTypedArray())
        newClass.isAnonymous = spec.isAnonymous
        newClass.constructorMeta = ArgsDeclaration(emptyList(), Token.Type.RPAREN)
        for (i in parentClasses.indices) {
            val argsList = spec.baseSpecs[i].args
            if (argsList != null) newClass.directParentArgs[parentClasses[i]] = argsList
        }

        val classScope = scope.createChildScope(newThisObj = newClass)
        if (!bodyCaptureRecords.isNullOrEmpty() && !bodyCaptureNames.isNullOrEmpty()) {
            classScope.captureRecords = bodyCaptureRecords
            classScope.captureNames = bodyCaptureNames
        }
        classScope.currentClassCtx = newClass
        newClass.classScope = classScope
        classScope.addConst("object", newClass)

        spec.bodyInit?.execute(classScope)

        val instance = newClass.callOn(scope.createChildScope(Arguments.EMPTY))
        if (spec.declaredName != null) {
            scope.addItem(spec.declaredName, false, instance)
        }
        return instance
    }

    if (spec.isExtern) {
        val rec = scope[spec.className]
        val existing = rec?.value as? ObjClass
        val resolved = if (existing != null) {
            existing
        } else if (spec.className.contains('.')) {
            scope.resolveQualifiedIdentifier(spec.className) as? ObjClass
        } else {
            null
        }
        val stub = resolved ?: ObjInstanceClass(spec.className).apply { this.isAbstract = true }
        spec.declaredName?.let { scope.addItem(it, false, stub) }
        return stub
    }

    val parentClasses = spec.baseSpecs.map { baseSpec ->
        val rec = scope[baseSpec.name]
        val cls = rec?.value as? ObjClass
        if (cls != null) return@map cls
        if (baseSpec.name == "Exception") return@map ObjException.Root
        if (rec == null) throw ScriptError(spec.startPos, "unknown base class: ${baseSpec.name}")
        throw ScriptError(spec.startPos, "${baseSpec.name} is not a class")
    }

    val constructorCode = object : Statement() {
        override val pos: Pos = spec.startPos
        override suspend fun execute(scope: Scope): Obj {
            val instance = scope.thisObj as ObjInstance
            return instance
        }
    }

    val newClass = ObjInstanceClass(spec.className, *parentClasses.toTypedArray()).also {
        it.isAbstract = spec.isAbstract
        it.instanceConstructor = constructorCode
        it.constructorMeta = spec.constructorArgs
        for (i in parentClasses.indices) {
            val argsList = spec.baseSpecs[i].args
            if (argsList != null) it.directParentArgs[parentClasses[i]] = argsList
        }
        spec.constructorArgs?.params?.forEach { p ->
            if (p.accessType != null) {
                it.createField(
                    p.name,
                    ObjNull,
                    isMutable = p.accessType == AccessType.Var,
                    visibility = p.visibility ?: Visibility.Public,
                    declaringClass = it,
                    pos = Pos.builtIn,
                    isTransient = p.isTransient,
                    type = ObjRecord.Type.ConstructorField,
                    fieldId = spec.constructorFieldIds?.get(p.name)
                )
            }
        }
    }

    spec.declaredName?.let { scope.addItem(it, false, newClass) }
    val classScope = scope.createChildScope(newThisObj = newClass)
    if (!bodyCaptureRecords.isNullOrEmpty() && !bodyCaptureNames.isNullOrEmpty()) {
        classScope.captureRecords = bodyCaptureRecords
        classScope.captureNames = bodyCaptureNames
    }
    classScope.currentClassCtx = newClass
    newClass.classScope = classScope
    spec.bodyInit?.execute(classScope)
    if (spec.initScope.isNotEmpty()) {
        for (s in spec.initScope) {
            s.execute(classScope)
        }
    }
    newClass.checkAbstractSatisfaction(spec.startPos)
    return newClass
}

class ClassDeclStatement(
    val spec: ClassDeclSpec,
) : Statement() {
    override val pos: Pos = spec.startPos
    val declaredName: String? get() = spec.declaredName
    val typeName: String get() = spec.typeName

    override suspend fun execute(scope: Scope): Obj {
        return executeClassDecl(scope, spec)
    }

    override suspend fun callOn(scope: Scope): Obj {
        val target = scope.parent ?: scope
        return executeClassDecl(target, spec)
    }
}
