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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.sergeych.lyng.*
import net.sergeych.lynon.LynonDecoder
import net.sergeych.lynon.LynonEncoder
import net.sergeych.lynon.LynonType
import net.sergeych.synctools.ProtectedOp

open class Obj {

    open val isConst: Boolean = false

    fun ensureNotConst(scope: Scope) {
        if (isConst) scope.raiseError("can't assign to constant")
    }

    val isNull by lazy { this === ObjNull }

    var isFrozen: Boolean = false

    private val monitor = Mutex()

    //    private val memberMutex = Mutex()
    internal var parentInstances: MutableList<Obj> = mutableListOf()

    private val opInstances = ProtectedOp()

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
            someClass == rootObjectType


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
        onNotFoundResult: (() -> Obj?)? = null
    ): Obj {
        return objClass.getInstanceMemberOrNull(name)?.value?.invoke(
            scope,
            this,
            args
        )
            ?: onNotFoundResult?.invoke()
            ?: scope.raiseSymbolNotFound(name)
    }

    open suspend fun getInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): Obj =
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(scope.pos, name).value

    fun getMemberOrNull(name: String): Obj? = objClass.getInstanceMemberOrNull(name)?.value

    // methods that to override

    open suspend fun compareTo(scope: Scope, other: Obj): Int {
        scope.raiseNotImplemented()
    }

    open suspend fun contains(scope: Scope, other: Obj): Boolean {
        return invokeInstanceMethod(scope, "contains", other).toBool()
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
    suspend open fun toString(scope: Scope,calledFromLyng: Boolean=false): ObjString {
        return if (this is ObjString) this
        else if( !calledFromLyng ) {
            invokeInstanceMethod(scope, "toString") {
                ObjString(this.toString())
            } as ObjString
        } else { ObjString(this.toString()) }
    }

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

    open suspend fun assign(scope: Scope, other: Obj): Obj? = null

    open fun getValue(scope: Scope) = this

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

    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    open suspend fun readField(scope: Scope, name: String): ObjRecord {
        // could be property or class field:
        val obj = objClass.getInstanceMemberOrNull(name) ?: scope.raiseError("no such field: $name")
        return when (val value = obj.value) {
            is Statement -> {
                ObjRecord(value.execute(scope.copy(scope.pos, newThisObj = this)), obj.isMutable)
            }
            // could be writable property naturally
//            null -> ObjNull.asReadonly
            else -> obj
        }
    }

    open suspend fun writeField(scope: Scope, name: String, newValue: Obj) {
        willMutate(scope)
        val field = objClass.getInstanceMemberOrNull(name) ?: scope.raiseError("no such field: $name")
        if (field.isMutable) field.value = newValue else scope.raiseError("can't assign to read-only field: $name")
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

    suspend fun invoke(scope: Scope, thisObj: Obj, args: Arguments): Obj =
        callOn(scope.copy(scope.pos, args = args, newThisObj = thisObj))

    suspend fun invoke(scope: Scope, thisObj: Obj, vararg args: Obj): Obj =
        callOn(
            scope.copy(
                scope.pos,
                args = Arguments(args.toList()),
                newThisObj = thisObj
            )
        )

    suspend fun invoke(scope: Scope, thisObj: Obj): Obj =
        callOn(
            scope.copy(
                scope.pos,
                args = Arguments.EMPTY,
                newThisObj = thisObj
            )
        )

    suspend fun invoke(scope: Scope, atPos: Pos, thisObj: Obj, args: Arguments): Obj =
        callOn(scope.copy(atPos, args = args, newThisObj = thisObj))


    val asReadonly: ObjRecord by lazy { ObjRecord(this, false) }
    val asMutable: ObjRecord by lazy { ObjRecord(this, true) }

    open suspend fun lynonType(): LynonType = LynonType.Other

    open suspend fun serialize(scope: Scope, encoder: LynonEncoder, lynonType: LynonType?) {
        scope.raiseNotImplemented()
    }

    fun autoInstanceScope(parent: Scope): Scope {
        val scope = parent.copy(newThisObj = this, args = parent.args)
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
                args.firstAndOnly().callOn(copy(Arguments(thisObj)))
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
                args.firstAndOnly().callOn(copy(Arguments(thisObj)))
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

        }


        inline fun from(obj: Any?): Obj {
            @Suppress("UNCHECKED_CAST")
            return when (obj) {
                is Obj -> obj
                is Double -> ObjReal(obj)
                is Float -> ObjReal(obj.toDouble())
                is Int -> ObjInt(obj.toLong())
                is Long -> ObjInt(obj)
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


