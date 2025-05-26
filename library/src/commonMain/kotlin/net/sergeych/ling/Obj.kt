package net.sergeych.ling

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

typealias InstanceMethod = (Context, Obj) -> Obj

data class WithAccess<T>(var value: T, val isMutable: Boolean)

data class Accessor(
    val getter: suspend (Context) -> Obj,
    val setterOrNull: (suspend (Context, Obj) -> Unit)?
) {
    constructor(getter: suspend (Context) -> Obj) : this(getter, null)

    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos,"can't assign value")
}

sealed class ClassDef(
    val className: String
) {
    val baseClasses: List<ClassDef> get() = emptyList()
    protected val instanceMembers: MutableMap<String, WithAccess<Obj>> = mutableMapOf()
    private val monitor = Mutex()


    suspend fun addInstanceMethod(
        context: Context,
        name: String,
        isOpen: Boolean = false,
        body: Obj
    ) {
        monitor.withLock {
            instanceMembers[name]?.let {
                if (!it.isMutable)
                    context.raiseError("method $name is not open and can't be overridden")
                it.value = body
            } ?: instanceMembers.put(name, WithAccess(body, isOpen))
        }
    }

    suspend fun getInstanceMethodOrNull(name: String): Obj? =
        monitor.withLock { instanceMembers[name]?.value }

    suspend fun getInstanceMethod(context: Context, name: String): Obj =
        getInstanceMethodOrNull(name) ?: context.raiseError("no method found: $name")

//    suspend fun callInstanceMethod(context: Context, name: String, self: Obj,args: Arguments): Obj {
//         getInstanceMethod(context, name).invoke(context, self,args)
//    }
}


object ObjClassDef : ClassDef("Obj")

sealed class Obj {
    open val classDef: ClassDef = ObjClassDef
    var isFrozen: Boolean = false

    protected val instanceMethods: Map<String, WithAccess<InstanceMethod>> = mutableMapOf()
    private val monitor = Mutex()

    open suspend fun compareTo(context: Context, other: Obj): Int {
        context.raiseNotImplemented()
    }

    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
    }

    open val definition: ClassDef = ObjClassDef

    open fun plus(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open fun assign(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open fun plusAssign(context: Context, other: Obj): Obj {
        assign(context, plus(context, other))
        return this
    }

    open fun getAndIncrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open fun incrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open fun decrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open fun getAndDecrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    fun willMutate(context: Context) {
        if (isFrozen) context.raiseError("attempt to mutate frozen object")
    }

    suspend fun getInstanceMember(context: Context, name: String): Obj? = definition.getInstanceMethodOrNull(name)

    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    open suspend fun readField(context: Context, name: String): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun writeField(context: Context,name: String, newValue: Obj) {
        context.raiseNotImplemented()
    }

    open suspend fun callOn(context: Context): Obj {
        context.raiseNotImplemented()
    }

    companion object {
        inline fun <reified T> from(obj: T): Obj {
            return when (obj) {
                is Obj -> obj
                is Double -> ObjReal(obj)
                is Float -> ObjReal(obj.toDouble())
                is Int -> ObjInt(obj.toLong())
                is Long -> ObjInt(obj)
                is String -> ObjString(obj)
                is CharSequence -> ObjString(obj.toString())
                is Boolean -> ObjBool(obj)
                Unit -> ObjVoid
                null -> ObjNull
                else -> throw IllegalArgumentException("cannot convert to Obj: $obj")
            }
        }
    }
}

@Suppress("unused")
inline fun <reified T> T.toObj(): Obj = Obj.from(this)

@Serializable
@SerialName("void")
object ObjVoid : Obj() {
    override fun equals(other: Any?): Boolean {
        return other is ObjVoid || other is Unit
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun toString(): String = "void"
}

@Serializable
@SerialName("null")
object ObjNull : Obj() {
    override suspend fun compareTo(context: Context, other: Obj): Int {
        return if (other === this) 0 else -1
    }

    override fun equals(other: Any?): Boolean {
        return other is ObjNull || other == null
    }
}

@Serializable
@SerialName("string")
data class ObjString(val value: String) : Obj() {

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjString) context.raiseError("cannot compare string with $other")
        return this.value.compareTo(other.value)
    }

    override fun toString(): String = value
}

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
    (this as? Numeric)?.longValue
        ?: (this as? ObjString)?.value?.toLong()
        ?: throw IllegalArgumentException("cannot convert to double $this")

fun Obj.toInt(): Int = toLong().toInt()

fun Obj.toBool(): Boolean =
    (this as? ObjBool)?.value ?: throw IllegalArgumentException("cannot convert to boolean $this")


@Serializable
@SerialName("real")
data class ObjReal(val value: Double) : Obj(), Numeric {
    override val asStr by lazy { ObjString(value.toString()) }
    override val longValue: Long by lazy { floor(value).toLong() }
    override val doubleValue: Double by lazy { value }
    override val toObjInt: ObjInt by lazy { ObjInt(longValue) }
    override val toObjReal: ObjReal by lazy { ObjReal(value) }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()
}

@Serializable
@SerialName("int")
data class ObjInt(var value: Long) : Obj(), Numeric {
    override val asStr get() = ObjString(value.toString())
    override val longValue get() = value
    override val doubleValue get() = value.toDouble()
    override val toObjInt get() = this
    override val toObjReal = ObjReal(doubleValue)

    override fun getAndIncrement(context: Context): Obj {
        return ObjInt(value).also { value++ }
    }

    override fun getAndDecrement(context: Context): Obj {
        return ObjInt(value).also { value-- }
    }

    override fun incrementAndGet(context: Context): Obj {
        return ObjInt(++value)
    }

    override fun decrementAndGet(context: Context): Obj {
        return ObjInt(--value)
    }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is Numeric) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.doubleValue)
    }

    override fun toString(): String = value.toString()
}

@Serializable
@SerialName("bool")
data class ObjBool(val value: Boolean) : Obj() {
    override val asStr by lazy { ObjString(value.toString()) }

    override suspend fun compareTo(context: Context, other: Obj): Int {
        if (other !is ObjBool) context.raiseError("cannot compare $this with $other")
        return value.compareTo(other.value)
    }

    override fun toString(): String = value.toString()
}

data class ObjNamespace(val name: String, val context: Context) : Obj() {
    override fun toString(): String {
        return "namespace ${name}"
    }

    override suspend fun readField(callerContext: Context,name: String): Obj {
        return context[name]?.value ?: callerContext.raiseError("not found: $name")
    }

}

open class ObjError(val context: Context, val message: String) : Obj() {
    override val asStr: ObjString by lazy { ObjString("Error: $message") }
}

class ObjNullPointerError(context: Context) : ObjError(context, "object is null")

class ObjClass(override val definition: ClassDef) : Obj() {

    override suspend fun compareTo(context: Context, other: Obj): Int {
//        definition.callInstanceMethod(":compareTo", context, other)?.let {
//            it(context, this)
//        }
        TODO("Not yet implemented")
    }

}