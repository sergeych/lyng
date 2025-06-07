package net.sergeych.lyng

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.sergeych.synctools.ProtectedOp

//typealias InstanceMethod = (Context, Obj) -> Obj

data class WithAccess<T>(
    var value: T,
    val isMutable: Boolean,
    val visibility: Compiler.Visibility = Compiler.Visibility.Public
)

data class Accessor(
    val getter: suspend (Context) -> WithAccess<Obj>,
    val setterOrNull: (suspend (Context, Obj) -> Unit)?
) {
    constructor(getter: suspend (Context) -> WithAccess<Obj>) : this(getter, null)

    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos, "can't assign value")
}

open class Obj {
    var isFrozen: Boolean = false

    private val monitor = Mutex()

    //    private val memberMutex = Mutex()
    internal var parentInstances: MutableList<Obj> = mutableListOf()

    private val opInstances = ProtectedOp()

    open fun inspect(): String = toString()

    /**
     * Some objects are by-value, historically [ObjInt] and [ObjReal] are usually treated as such.
     * When initializing a var with it, by value objects must be copied. By-reference ones aren't.
     *
     * Almost all objects are by-reference.
     */
    open fun byValueCopy(): Obj = this

    fun isInstanceOf(someClass: Obj) = someClass === objClass || objClass.allParentsSet.contains(someClass)

    suspend fun invokeInstanceMethod(context: Context, name: String, vararg args: Obj): Obj =
        invokeInstanceMethod(context, name, Arguments(args.map { Arguments.Info(it, context.pos) }))

    inline suspend fun <reified T : Obj> callMethod(
        context: Context,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): T = invokeInstanceMethod(context, name, args) as T

    suspend fun invokeInstanceMethod(
        context: Context,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): Obj =
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(context.pos, name).value.invoke(context, this, args)

    fun getMemberOrNull(name: String): Obj? = objClass.getInstanceMemberOrNull(name)?.value

    // methods that to override

    open suspend fun compareTo(context: Context, other: Obj): Int {
        context.raiseNotImplemented()
    }

    open suspend fun contains(context: Context, other: Obj): Boolean {
        context.raiseNotImplemented()
    }

    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
    }

    /**
     * Class of the object: definition of member functions (top-level), etc.
     * Note that using lazy allows to avoid endless recursion here
     */
    open val objClass: ObjClass by lazy {
        ObjClass("Obj").apply {
            addFn("toString") {
                thisObj.asStr
            }
            addFn("contains") {
                ObjBool(thisObj.contains(this, args.firstAndOnly()))
            }
        }
    }

    open suspend fun plus(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun minus(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun mul(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun div(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun mod(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalNot(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalAnd(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun logicalOr(context: Context, other: Obj): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun assign(context: Context, other: Obj): Obj? = null

    /**
     * a += b
     * if( the operation is not defined, it returns null and the compiler would try
     * to generate it as 'this = this + other', reassigning its variable
     */
    open suspend fun plusAssign(context: Context, other: Obj): Obj? = null

    /**
     * `-=` operations, see [plusAssign]
     */
    open suspend fun minusAssign(context: Context, other: Obj): Obj? = null
    open suspend fun mulAssign(context: Context, other: Obj): Obj? = null
    open suspend fun divAssign(context: Context, other: Obj): Obj? = null
    open suspend fun modAssign(context: Context, other: Obj): Obj? = null

    open suspend fun getAndIncrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun incrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun decrementAndGet(context: Context): Obj {
        context.raiseNotImplemented()
    }

    open suspend fun getAndDecrement(context: Context): Obj {
        context.raiseNotImplemented()
    }

    fun willMutate(context: Context) {
        if (isFrozen) context.raiseError("attempt to mutate frozen object")
    }

    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    suspend fun readField(context: Context, name: String): WithAccess<Obj> {
        // could be property or class field:
        val obj = objClass.getInstanceMemberOrNull(name)
        val value = obj?.value
        return when (value) {
            is Statement -> {
                // readonly property, important: call it on this
                value.execute(context.copy(context.pos, newThisObj = this)).asReadonly
            }
            // could be writable property naturally
            null -> ObjNull.asReadonly
            else -> obj
        }
    }

    fun writeField(context: Context, name: String, newValue: Obj) {
        willMutate(context)
        val field = objClass.getInstanceMemberOrNull(name) ?: context.raiseError("no such field: $name")
        if (field.isMutable) field.value = newValue else context.raiseError("can't assign to read-only field: $name")
    }

    open suspend fun getAt(context: Context, index: Int): Obj {
        context.raiseNotImplemented("indexing")
    }

    open suspend fun putAt(context: Context, index: Int, newValue: Obj) {
        context.raiseNotImplemented("indexing")
    }

    open suspend fun callOn(context: Context): Obj {
        context.raiseNotImplemented()
    }

    suspend fun invoke(context: Context, thisObj: Obj, args: Arguments): Obj =
        callOn(context.copy(context.pos, args = args, newThisObj = thisObj))

    suspend fun invoke(context: Context, thisObj: Obj, vararg args: Obj): Obj =
        callOn(
            context.copy(
                context.pos,
                args = Arguments(args.map { Arguments.Info(it, context.pos) }),
                newThisObj = thisObj
            )
        )

    suspend fun invoke(context: Context, thisObj: Obj): Obj =
        callOn(
            context.copy(
                context.pos,
                args = Arguments.EMPTY,
                newThisObj = thisObj
            )
        )

    suspend fun invoke(context: Context, atPos: Pos, thisObj: Obj, args: Arguments): Obj =
        callOn(context.copy(atPos, args = args, newThisObj = thisObj))


    val asReadonly: WithAccess<Obj> by lazy { WithAccess(this, false) }
    val asMutable: WithAccess<Obj> by lazy { WithAccess(this, true) }


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

    override fun inspect(): String = "Ns[$name]"

    override fun toString(): String {
        return "package $name"
    }
}

open class ObjError(val context: Context, val message: String) : Obj() {
    override val asStr: ObjString by lazy { ObjString("Error: $message") }
}

class ObjNullPointerError(context: Context) : ObjError(context, "object is null")

class ObjAssertionError(context: Context, message: String) : ObjError(context, message)
class ObjClassCastError(context: Context, message: String) : ObjError(context, message)
class ObjIndexOutOfBoundsError(context: Context, message: String = "index out of bounds") : ObjError(context, message)
class ObjIllegalArgumentError(context: Context, message: String = "illegal argument") : ObjError(context, message)

class ObjIterationFinishedError(context: Context) : ObjError(context, "iteration finished")