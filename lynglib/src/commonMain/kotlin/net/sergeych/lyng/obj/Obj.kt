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

package net.sergeych.lyng.obj

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import net.sergeych.lyng.*
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType

open class Obj {

    open val isConst: Boolean = false

    fun ensureNotConst(scope: Scope) {
        if (isConst) scope.raiseError("can't assign to constant")
    }

    val isNull by lazy { this === ObjNull }

    var isFrozen: Boolean = false

//    private val monitor = Mutex()

    //    private val memberMutex = Mutex()
//    internal var parentInstances: MutableList<Obj> = mutableListOf()

//    private val opInstances = ProtectedOp()

    open suspend fun inspect(scope: Scope): String = toString(scope).value

    /**
     * Some objects are by-value, historically [ObjInt] and [ObjReal] are usually treated as such.
     * When initializing a var with it, by value objects must be copied. By-reference ones aren't.
     *
     * Almost all objects are by-reference.
     */
    open fun byValueCopy(): Obj = this

    @Suppress("SuspiciousEqualsCombination")
    fun isInstanceOf(someClass: Obj) = someClass === objClass ||
            objClass.allParentsSet.contains(someClass) ||
            someClass == rootObjectType ||
            (someClass is ObjClass && objClass.allImplementingNames.contains(someClass.className))


    suspend fun invokeInstanceMethod(scope: Scope, name: String, vararg args: Obj): Obj =
        invokeInstanceMethod(scope, name, Arguments(args.toList()))

    suspend inline fun <reified T : Obj> callMethod(
        scope: Scope,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): T =
        invokeInstanceMethod(scope, name, args) as T

    /**
     * Invoke a method of the object if exists
     * it [onNotFoundResult] is not null, it returns it when symbol is not found
     * otherwise throws [ObjSymbolNotDefinedException] object exception
     */
    open suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments = Arguments.EMPTY,
        onNotFoundResult: (suspend () -> Obj?)? = null
    ): Obj {
        // 1. Hierarchy members (excluding root fallback)
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null && !rec.isAbstract && rec.type != ObjRecord.Type.Property) {
                val decl = rec.declaringClass ?: cls
                val caller = scope.currentClassCtx
                if (!canAccessMember(rec.visibility, decl, caller))
                    scope.raiseError(ObjIllegalAccessException(scope, "can't invoke ${name}: not visible (declared in ${decl.className}, caller ${caller?.className ?: "?"})"))
                return rec.value.invoke(scope, this, args, decl)
            }
        }

        // 2. Extensions in scope
        val extension = scope.findExtension(objClass, name)
        if (extension != null) {
            return extension.value.invoke(scope, this, args)
        }

        // 3. Root object fallback
        for (cls in objClass.mro) {
            if (cls.className == "Obj") {
                cls.members[name]?.let { rec ->
                    val decl = rec.declaringClass ?: cls
                    val caller = scope.currentClassCtx
                    if (!canAccessMember(rec.visibility, decl, caller))
                        scope.raiseError(ObjIllegalAccessException(scope, "can't invoke ${name}: not visible (declared in ${decl.className}, caller ${caller?.className ?: "?"})"))
                    return rec.value.invoke(scope, this, args, decl)
                }
            }
        }

        return onNotFoundResult?.invoke()
            ?: scope.raiseError(
                "no such member: $name on ${objClass.className}. Considered order: ${objClass.renderLinearization(true)}. " +
                        "Tip: try this@Base.$name(...) or (obj as Base).$name(...) if ambiguous"
            )
    }

    open suspend fun getInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): Obj =
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(scope.pos, name).value

//    fun getMemberOrNull(name: String): Obj? = objClass.getInstanceMemberOrNull(name)?.value

    // methods that to override

    open suspend fun compareTo(scope: Scope, other: Obj): Int {
        if (other === this) return 0
        if (other === ObjNull || other === ObjUnset || other === ObjVoid) return 2
        scope.raiseNotImplemented()
    }

    open suspend fun equals(scope: Scope, other: Obj): Boolean {
        if (other === this) return true
        return try {
            compareTo(scope, other) == 0
        } catch (e: ExecutionError) {
            false
        }
    }

    open suspend fun contains(scope: Scope, other: Obj): Boolean {
        return invokeInstanceMethod(scope, "contains", other).toBool()
    }

    /**
     * Call [callback] for each element of this obj considering it provides [Iterator]
     * methods `hasNext` and `next`.
     *
     * IF callback returns false, iteration is stopped.
     */
    open suspend fun enumerate(scope: Scope, callback: suspend (Obj) -> Boolean) {
        val iterator = invokeInstanceMethod(scope, "iterator")
        val hasNext = iterator.getInstanceMethod(scope, "hasNext")
        val next = iterator.getInstanceMethod(scope, "next")
        var closeIt = false
        try {
            while (hasNext.invoke(scope, iterator).toBool()) {
                val nextValue = next.invoke(scope, iterator)
                val shouldContinue = try {
                    callback(nextValue)
                } catch (e: Exception) {
                    // iteration aborted due to exception in callback
                    closeIt = true
                    throw e
                }
                if (!shouldContinue) {
                    closeIt = true
                    break
                }
            }
        } finally {
            if (closeIt) {
                // Best-effort cancel on premature termination
                iterator.invokeInstanceMethod(scope, "cancelIteration") { ObjVoid }
            }
        }
    }

    /**
     * Default toString implementation:
     *
     * - if the object is a string, returns it
     * - otherwise, if not [calledFromLyng], calls Lyng override `toString()` if exists
     * - otherwise, meaning either called from Lyng `toString`, or there is no
     *   Lyng override, returns the object's Kotlin variant of `toString()
     *
     * Note on kotlin's `toString()`: it is preferred to use this, 'scoped` version,
     * as it can execute Lyng code using the scope and being suspending one.
     *
     * @param scope the scope where the string representation was requested
     * @param calledFromLyng true if called from Lyng's `toString`. Normally this parameter should be ignored,
     *      but it is used to avoid endless recursion in [Obj.toString] base implementation
     */
    open suspend fun toString(scope: Scope, calledFromLyng: Boolean = false): ObjString {
        if (this is ObjString) return this
        return if (!calledFromLyng) {
            invokeInstanceMethod(scope, "toString", Arguments.EMPTY) {
                defaultToString(scope)
            } as ObjString
        } else {
            defaultToString(scope)
        }
    }

    open suspend fun defaultToString(scope: Scope): ObjString = ObjString(this.toString())

    /**
     * Class of the object: definition of member functions (top-level), etc.
     * Note that using lazy allows to avoid endless recursion here
     */
    open val objClass: ObjClass = rootObjectType

    open suspend fun plus(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun minus(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun negate(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun mul(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun div(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun mod(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun logicalNot(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun logicalAnd(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun logicalOr(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun operatorMatch(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun operatorNotMatch(scope: Scope, other: Obj): Obj {
        return operatorMatch(scope,other).logicalNot(scope)
    }

    // Bitwise ops default (override in numeric types that support them)
    open suspend fun bitAnd(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun bitOr(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun bitXor(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun shl(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun shr(scope: Scope, other: Obj): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun bitNot(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun assign(scope: Scope, other: Obj): Obj? = null

    open suspend fun getValue(scope: Scope) = this

    /**
     * a += b
     * if( the operation is not defined, it returns null and the compiler would try
     * to generate it as 'this = this + other', reassigning its variable
     */
    open suspend fun plusAssign(scope: Scope, other: Obj): Obj? = null

    /**
     * `-=` operations, see [plusAssign]
     */
    open suspend fun minusAssign(scope: Scope, other: Obj): Obj? = null
    open suspend fun mulAssign(scope: Scope, other: Obj): Obj? = null
    open suspend fun divAssign(scope: Scope, other: Obj): Obj? = null
    open suspend fun modAssign(scope: Scope, other: Obj): Obj? = null

    open suspend fun getAndIncrement(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun incrementAndGet(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun decrementAndGet(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    open suspend fun getAndDecrement(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    /**
     * Convert Lyng object to its Kotlin counterpart
     */
    open suspend fun toKotlin(scope: Scope): Any? {
        return toString()
    }

    fun willMutate(scope: Scope) {
        if (isFrozen) scope.raiseError("attempt to mutate frozen object")
    }

//    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    open suspend fun readField(scope: Scope, name: String): ObjRecord {
        // 1. Hierarchy members (excluding root fallback)
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null) {
                if (!rec.isAbstract) {
                    return resolveRecord(scope, rec, name, rec.declaringClass)
                }
            }
        }

        // 2. Extensions
        val extension = scope.findExtension(objClass, name)
        if (extension != null) {
            return resolveRecord(scope, extension, name, extension.declaringClass)
        }

        // 3. Root fallback
        for (cls in objClass.mro) {
            if (cls.className == "Obj") {
                cls.members[name]?.let {
                    val decl = it.declaringClass ?: cls
                    return resolveRecord(scope, it, name, decl)
                }
            }
        }

        scope.raiseError(
            "no such field: $name on ${objClass.className}. Considered order: ${objClass.renderLinearization(true)}"
        )
    }

    open suspend fun resolveRecord(scope: Scope, obj: ObjRecord, name: String, decl: ObjClass?): ObjRecord {
        if (obj.type == ObjRecord.Type.Delegated) {
            val del = obj.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate")
            return obj.copy(
                value = del.invokeInstanceMethod(scope, "getValue", Arguments(this, ObjString(name))),
                type = ObjRecord.Type.Other
            )
        }
        val value = obj.value
        if (value is ObjProperty) {
            return ObjRecord(value.callGetter(scope, this, decl), obj.isMutable)
        }
        if (value is Statement && decl != null) {
            return ObjRecord(value.execute(scope.createChildScope(scope.pos, newThisObj = this)), obj.isMutable)
        }
        val caller = scope.currentClassCtx
        // Check visibility for non-property members here if they weren't checked before
        if (!canAccessMember(obj.visibility, decl, caller))
            scope.raiseError(ObjIllegalAccessException(scope, "can't access field ${name}: not visible (declared in ${decl?.className ?: "?"}, caller ${caller?.className ?: "?"})"))
        return obj
    }

    open suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        willMutate(scope)
        var field: ObjRecord? = null
        // 1. Hierarchy members (excluding root fallback)
        for (cls in objClass.mro) {
            if (cls.className == "Obj") break
            val rec = cls.members[name] ?: cls.classScope?.objects?.get(name)
            if (rec != null && !rec.isAbstract) {
                field = rec
                break
            }
        }
        // 2. Extensions
        if (field == null) {
            field = scope.findExtension(objClass, name)
        }
        // 3. Root fallback
        if (field == null) {
            for (cls in objClass.mro) {
                if (cls.className == "Obj") {
                    field = cls.members[name]
                    if (field != null) break
                }
            }
        }

        if (field == null) scope.raiseError(
            "no such field: $name on ${objClass.className}. Considered order: ${objClass.renderLinearization(true)}"
        )

        val decl = field.declaringClass
        val caller = scope.currentClassCtx
        if (!canAccessMember(field.effectiveWriteVisibility, decl, caller))
            scope.raiseError(ObjIllegalAccessException(scope, "can't assign field ${name}: not visible (declared in ${decl?.className ?: "?"}, caller ${caller?.className ?: "?"})"))
        if (field.type == ObjRecord.Type.Delegated) {
            val del = field.delegate ?: scope.raiseError("Internal error: delegated property $name has no delegate")
            del.invokeInstanceMethod(scope, "setValue", Arguments(this, ObjString(name), newValue))
        } else if (field.value is ObjProperty) {
            (field.value as ObjProperty).callSetter(scope, this, newValue, decl)
        } else if (field.isMutable) field.value = newValue else scope.raiseError("can't assign to read-only field: $name")
    }

    open suspend fun getAt(scope: Scope, index: Obj): Obj {
        scope.raiseNotImplemented("indexing")
    }

    suspend fun getAt(scope: Scope, index: Int): Obj = getAt(scope, ObjInt(index.toLong()))

    open suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        scope.raiseNotImplemented("indexing")
    }

    open suspend fun callOn(scope: Scope): Obj {
        scope.raiseNotImplemented()
    }

    suspend fun invoke(scope: Scope, thisObj: Obj, args: Arguments, declaringClass: ObjClass? = null): Obj =
        if (PerfFlags.SCOPE_POOL)
            scope.withChildFrame(args, newThisObj = thisObj) { child ->
                if (declaringClass != null) child.currentClassCtx = declaringClass
                callOn(child)
            }
        else
            callOn(scope.createChildScope(scope.pos, args = args, newThisObj = thisObj).also {
                if (declaringClass != null) it.currentClassCtx = declaringClass
            })

    suspend fun invoke(scope: Scope, thisObj: Obj, vararg args: Obj): Obj =
        callOn(
            scope.createChildScope(
                scope.pos,
                args = Arguments(args.toList()),
                newThisObj = thisObj
            )
        )

    suspend fun invoke(scope: Scope, thisObj: Obj): Obj =
        callOn(
            scope.createChildScope(
                scope.pos,
                args = Arguments.EMPTY,
                newThisObj = thisObj
            )
        )

    suspend fun invoke(scope: Scope, atPos: Pos, thisObj: Obj, args: Arguments): Obj =
        callOn(scope.createChildScope(atPos, args = args, newThisObj = thisObj))


    val asReadonly: ObjRecord by lazy { ObjRecord(this, false) }
    val asMutable: ObjRecord by lazy { ObjRecord(this, true) }

    open suspend fun lynonType(): LynonType = LynonType.Other

    open suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        scope.raiseNotImplemented("lynon: can't serialize ${this.objClass}:${this.toString(scope)}")
    }

    fun autoInstanceScope(parent: Scope): Scope {
        // Create a stable instance scope whose parent is the provided parent scope directly,
        // not a transient child that could be pooled and reset. This preserves proper name
        // resolution (e.g., stdlib functions like sqrt) even when call frame pooling is enabled.
        val scope = Scope(parent, parent.args, parent.pos, this)
        for (m in objClass.members) {
            scope.objects[m.key] = m.value
        }
        return scope
    }

    inline fun <reified R: Obj> cast(scope: Scope): R {
        castOrNull<R>()?.let { return it }
        scope.raiseClassCastError("can't cast ${this::class.simpleName} to ${R::class.simpleName}")

    }

    inline fun <reified R: Obj> castOrNull(): R? {
        (this as? R)?.let { return it }
        // todo: check for subclasses
        return null
    }

    open suspend fun toJson(scope: Scope = Scope()): JsonElement {
        scope.raiseNotImplemented("toJson for ${objClass.className}")
    }

    companion object {

        val rootObjectType = ObjClass("Obj").apply {
            addFn("toString", true) {
                thisObj.toString(this, true)
            }
            addFn("inspect", true) {
                thisObj.inspect(this).toObj()
            }
            addFn("contains") {
                ObjBool(thisObj.contains(this, args.firstAndOnly()))
            }
            // utilities
            addFn("let") {
                args.firstAndOnly().callOn(createChildScope(Arguments(thisObj)))
            }
            addFn("apply") {
                val body = args.firstAndOnly()
                (thisObj as? ObjInstance)?.let {
                    body.callOn(ApplyScope(this, it.instanceScope))
                } ?: run {
                    body.callOn(this)
                }
                thisObj
            }
            addFn("also") {
                args.firstAndOnly().callOn(createChildScope(Arguments(thisObj)))
                thisObj
            }
            addFn("run") {
                args.firstAndOnly().callOn(this)
            }
            addFn("getAt") {
                requireExactCount(1)
                thisObj.getAt(this, requiredArg<Obj>(0))
            }
            addFn("putAt") {
                requireExactCount(2)
                val newValue = args[1]
                thisObj.putAt(this, requiredArg<Obj>(0), newValue)
                newValue
            }
            addFn("toJsonString") {
                thisObj.toJson(this).toString().toObj()
            }
        }


        @Suppress("NOTHING_TO_INLINE")
        inline fun from(obj: Any?): Obj {
            @Suppress("UNCHECKED_CAST")
            return when (obj) {
                is Obj -> obj
                is Double -> ObjReal(obj)
                is Float -> ObjReal(obj.toDouble())
                is Int -> ObjInt.of(obj.toLong())
                is Long -> ObjInt.of(obj)
                is String -> ObjString(obj)
                is CharSequence -> ObjString(obj.toString())
                is Boolean -> ObjBool(obj)
                is Set<*> -> ObjSet((obj as Set<Obj>).toMutableSet())
                Unit -> ObjVoid
                null -> ObjNull
                is Iterator<*> -> ObjKotlinIterator(obj)
                is Map.Entry<*, *> -> {
                    obj as MutableMap.MutableEntry<Obj, Obj>
                    ObjMapEntry(obj.key, obj.value)
                }

                else -> throw IllegalArgumentException("cannot convert to Obj: $obj")
            }
        }
    }
}

fun Double.toObj(): Obj = ObjReal(this)

@Suppress("unused")
inline fun <reified T> T.toObj(): Obj = Obj.from(this)

@Serializable
@SerialName("void")
object ObjVoid : Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjVoid || other is Unit
    }

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun toString(): String = "void"
}

@Serializable
@SerialName("null")
object ObjNull : Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun equals(other: Any?): Boolean {
        return other is ObjNull || other == null
    }

    override suspend fun readField(scope: Scope, name: String): ObjRecord {
        scope.raiseNPE()
    }

//    override suspend fun invokeInstanceMethod(
//        scope: Scope,
//        name: String,
//        args: Arguments,
//        onNotFoundResult: (()->Obj?)?
//    ): Obj {
//        scope.raiseNPE()
//    }

    override suspend fun getAt(scope: Scope, index: Obj): Obj {
        scope.raiseNPE()
    }

    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) {
        scope.raiseNPE()
    }

    override suspend fun callOn(scope: Scope): Obj {
        scope.raiseNPE()
    }

    override fun toString(): String = "null"

    override suspend fun toKotlin(scope: Scope): Any? {
        return null
    }

    override suspend fun lynonType(): LynonType {
        return LynonType.Null
    }

    override suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        if (lynonType == null) {
            encoder.putBit(0)
        }
    }

    override suspend fun toJson(scope: Scope): JsonElement {
        return JsonNull
    }

    override val objClass: ObjClass by lazy {
        object : ObjClass("Null") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                if (lynonType == LynonType.Null)
                    return this@ObjNull
                else
                    scope.raiseIllegalState("can't deserialize null directly or with wrong type: ${lynonType}")
            }
        }
    }
}

@Serializable
@SerialName("unset")
object ObjUnset : Obj() {
    override suspend fun compareTo(scope: Scope, other: Obj): Int = if (other === this) 0 else -1
    override fun equals(other: Any?): Boolean = other === this
    override fun toString(): String = "Unset"

    override suspend fun readField(scope: Scope, name: String): ObjRecord = scope.raiseUnset()
    override suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments,
        onNotFoundResult: (suspend () -> Obj?)?
    ): Obj = scope.raiseUnset()

    override suspend fun getAt(scope: Scope, index: Obj): Obj = scope.raiseUnset()
    override suspend fun putAt(scope: Scope, index: Obj, newValue: Obj) = scope.raiseUnset()
    override suspend fun callOn(scope: Scope): Obj = scope.raiseUnset()
    override suspend fun plus(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun minus(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun negate(scope: Scope): Obj = scope.raiseUnset()
    override suspend fun mul(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun div(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun mod(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun logicalNot(scope: Scope): Obj = scope.raiseUnset()
    override suspend fun logicalAnd(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun logicalOr(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun operatorMatch(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun bitAnd(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun bitOr(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun bitXor(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun shl(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun shr(scope: Scope, other: Obj): Obj = scope.raiseUnset()
    override suspend fun bitNot(scope: Scope): Obj = scope.raiseUnset()

    override val objClass: ObjClass by lazy {
        object : ObjClass("Unset") {
            override suspend fun deserialize(scope: Scope, decoder: LynonDecoder, lynonType: LynonType?): Obj {
                return ObjUnset
            }
        }
    }
}

/**
 * TODO: get rid of it. Maybe we ise some Lyng inheritance instead
 */
interface Numeric {
    val longValue: Long
    val doubleValue: Double
    val toObjInt: ObjInt
    val toObjReal: ObjReal
}

fun Obj.toDouble(): Double =
    (this as? Numeric)?.doubleValue
        ?: (this as? ObjString)?.value?.toDouble()
        ?: throw IllegalArgumentException("cannot convert to double $this")

@Suppress("unused")
fun Obj.toLong(): Long =
    when (this) {
        is Numeric -> longValue
        is ObjString -> value.toLong()
        is ObjChar -> value.code.toLong()
        else -> throw IllegalArgumentException("cannot convert to double $this")
    }

fun Obj.toInt(): Int = toLong().toInt()

fun Obj.toBool(): Boolean =
    (this as? ObjBool)?.value ?: throw IllegalArgumentException("cannot convert to boolean $this")


data class ObjNamespace(val name: String) : Obj() {
    override val objClass by lazy { ObjClass(name) }

    override suspend fun inspect(scope: Scope): String = "Ns[$name]"

    override fun toString(): String {
        return "package $name"
    }
}

/**
 * Decodes the current object into a deserialized form using the provided deserialization strategy.
 * It is based on [Obj.toJson] and uses existing Kotlin Json serialization, without string representation
 * (only `JsonElement` to carry information between Kotlin and Lyng serialization worlds), thus efficient.
 *
 * @param strategy The deserialization strategy that defines how the object should be decoded.
 * @param scope An optional scope used during deserialization to define the context. Defaults to a new instance of Scope.
 * @return The deserialized object of type T.
 */
suspend fun <T>Obj.decodeSerializableWith(strategy: DeserializationStrategy<T>, scope: Scope = Scope()): T =
    Json.decodeFromJsonElement(strategy,toJson(scope))

/**
 * Decodes a serializable object of type [T] using the provided decoding scope. The deserialization uses
 * [Obj.toJson] and existing Json based serialization ithout using actual string representation, thus
 * efficient.
 *
 * @param T The type of the object to be decoded. Must be a reified type.
 * @param scope The scope used during decoding. Defaults to a new instance of [Scope].
 */
suspend inline fun <reified T>Obj.decodeSerializable(scope: Scope= Scope()) =
    decodeSerializableWith<T>(serializer<T>(), scope)
