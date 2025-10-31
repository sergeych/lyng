/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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

package net.sergeych.lyng.obj

import net.sergeych.lyng.*
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonType

val ObjClassType by lazy { ObjClass("Class") }

open class ObjClass(
    val className: String,
    vararg parents: ObjClass,
) : Obj() {

    val classNameObj by lazy { ObjString(className) }

    var constructorMeta: ArgsDeclaration? = null
    var instanceConstructor: Statement? = null

    /**
     * the scope for class methods, initialize class vars, etc.
     *
     * Important notice. When create a user class, e.g. from Lyng source, it should
     * be set to a scope by compiler, so it could access local closure, etc. Otherwise,
     * it will be initialized to default scope on first necessity, e.g. when used in
     * external, kotlin classes with [addClassConst] and [addClassFn], etc.
     */
    var classScope: Scope? = null

    val allParentsSet: Set<ObjClass> =
        parents.flatMap {
            listOf(it) + it.allParentsSet
        }.toMutableSet()

    override val objClass: ObjClass by lazy { ObjClassType }

    /**
     * members: fields most often. These are called with [ObjInstance] withs ths [ObjInstance.objClass]
     */
    internal val members = mutableMapOf<String, ObjRecord>()

    override fun toString(): String = className

    override suspend fun compareTo(scope: Scope, other: Obj): Int = if (other === this) 0 else -1

    override suspend fun callOn(scope: Scope): Obj {
        val instance = ObjInstance(this)
        instance.instanceScope = scope.createChildScope(newThisObj = instance, args = scope.args)
        if (instanceConstructor != null) {
            instanceConstructor!!.execute(instance.instanceScope)
        }
        return instance
    }

    suspend fun callWithArgs(scope: Scope, vararg plainArgs: Obj): Obj {
        return callOn(scope.createChildScope(Arguments(*plainArgs)))
    }


    fun createField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        val existing = members[name] ?: allParentsSet.firstNotNullOfOrNull { it.members[name] }
        if (existing?.isMutable == false)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        members[name] = ObjRecord(initialValue, isMutable, visibility)
    }

    private fun initClassScope(): Scope {
        if (classScope == null) classScope = Scope()
        return classScope!!
    }

    fun createClassField(
        name: String,
        initialValue: Obj,
        isMutable: Boolean = false,
        visibility: Visibility = Visibility.Public,
        pos: Pos = Pos.builtIn
    ) {
        initClassScope()
        val existing = classScope!!.objects[name]
        if (existing != null)
            throw ScriptError(pos, "$name is already defined in $objClass or one of its supertypes")
        classScope!!.addItem(name, isMutable, initialValue, visibility)
    }

    fun addFn(name: String, isOpen: Boolean = false, code: suspend Scope.() -> Obj) {
        createField(name, statement { code() }, isOpen)
    }

    fun addConst(name: String, value: Obj) = createField(name, value, isMutable = false)
    fun addClassConst(name: String, value: Obj) = createClassField(name, value)
    fun addClassFn(name: String, isOpen: Boolean = false, code: suspend Scope.() -> Obj) {
        createClassField(name, statement { code() }, isOpen)
    }


    /**
     * Get instance member traversing the hierarchy if needed. Its meaning is different for different objects.
     */
    fun getInstanceMemberOrNull(name: String): ObjRecord? {
        members[name]?.let { return it }
        allParentsSet.forEach { parent -> parent.getInstanceMemberOrNull(name)?.let { return it } }
        return rootObjectType.members[name]
    }

    fun getInstanceMember(atPos: Pos, name: String): ObjRecord =
        getInstanceMemberOrNull(name)
            ?: throw ScriptError(atPos, "symbol doesn't exist: $name")

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        classScope?.objects?.get(name)?.let {
            if (it.visibility.isPublic) return it
        }
        return super.readField(scope, name)
    }

    override suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        initClassScope().objects[name]?.let {
            if (it.isMutable) it.value = newValue
            else scope.raiseIllegalAssignment("can't assign $name is not mutable")
        }
            ?: super.writeField(scope, name, newValue)
    }

    override suspend fun invokeInstanceMethod(
        scope: Scope, name: String, args: Arguments,
        onNotFoundResult: (() -> Obj?)?
    ): Obj {
        return classScope?.objects?.get(name)?.value?.invoke(scope, this, args)
            ?: super.invokeInstanceMethod(scope, name, args, onNotFoundResult)
    }

    open suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj =
        scope.raiseNotImplemented()

}


