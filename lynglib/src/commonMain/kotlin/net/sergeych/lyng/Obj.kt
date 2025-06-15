package net.sergeych.lyng

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.sergeych.bintools.encodeToHex
import net.sergeych.synctools.ProtectedOp
import net.sergeych.synctools.withLock
import kotlin.contracts.ExperimentalContracts

/**
 * Record to store object with access rules, e.g. [isMutable] and access level [visibility].
 */
data class ObjRecord(
    var value: Obj,
    val isMutable: Boolean,
    val visibility: Visibility = Visibility.Public
)

/**
 * When we need read-write access to an object in some abstract storage, we need Accessor,
 * as in-site assigning is not always sufficient, in general case we need to replace the object
 * in the storage.
 *
 * Note that assigning new value is more complex than just replacing the object, see how assignment
 * operator is implemented in [Compiler.allOps].
 */
data class Accessor(
    val getter: suspend (Context) -> ObjRecord,
    val setterOrNull: (suspend (Context, Obj) -> Unit)?
) {
    /**
     * Simplified constructor for immutable stores.
     */
    constructor(getter: suspend (Context) -> ObjRecord) : this(getter, null)

    /**
     * Get the setter or throw.
     */
    fun setter(pos: Pos) = setterOrNull ?: throw ScriptError(pos, "can't assign value")
}

open class Obj {

    val isNull by lazy { this === ObjNull }

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
        invokeInstanceMethod(context, name, Arguments(args.toList()))

    suspend inline fun <reified T : Obj> callMethod(
        context: Context,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): T = invokeInstanceMethod(context, name, args) as T

    open suspend fun invokeInstanceMethod(
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
        return invokeInstanceMethod(context, "contains", other).toBool()
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

    /**
     * Convert Lyng object to its Kotlin counterpart
     */
    open suspend fun toKotlin(context: Context): Any? {
        return toString()
    }

    fun willMutate(context: Context) {
        if (isFrozen) context.raiseError("attempt to mutate frozen object")
    }

    suspend fun <T> sync(block: () -> T): T = monitor.withLock { block() }

    open suspend fun readField(context: Context, name: String): ObjRecord {
        // could be property or class field:
        val obj = objClass.getInstanceMemberOrNull(name) ?: context.raiseError("no such field: $name")
        return when (val value = obj.value) {
            is Statement -> {
                ObjRecord(value.execute(context.copy(context.pos, newThisObj = this)), obj.isMutable)
            }
            // could be writable property naturally
//            null -> ObjNull.asReadonly
            else -> obj
        }
    }

    open suspend fun writeField(context: Context, name: String, newValue: Obj) {
        willMutate(context)
        val field = objClass.getInstanceMemberOrNull(name) ?: context.raiseError("no such field: $name")
        if (field.isMutable) field.value = newValue else context.raiseError("can't assign to read-only field: $name")
    }

    open suspend fun getAt(context: Context, index: Obj): Obj {
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
                args = Arguments(args.toList()),
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


    val asReadonly: ObjRecord by lazy { ObjRecord(this, false) }
    val asMutable: ObjRecord by lazy { ObjRecord(this, true) }


    companion object {
        inline fun <reified T> from(obj: T): Obj {
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

    override suspend fun readField(context: Context, name: String): ObjRecord {
        context.raiseNPE()
    }

    override suspend fun invokeInstanceMethod(context: Context, name: String, args: Arguments): Obj {
        context.raiseNPE()
    }

    override suspend fun getAt(context: Context, index: Obj): Obj {
        context.raiseNPE()
    }

    override suspend fun putAt(context: Context, index: Int, newValue: Obj) {
        context.raiseNPE()
    }

    override suspend fun callOn(context: Context): Obj {
        context.raiseNPE()
    }

    override fun toString(): String = "null"

    override suspend fun toKotlin(context: Context): Any? {
        return null
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

open class ObjException(exceptionClass: ExceptionClass, val context: Context, val message: String) : Obj() {
    constructor(name: String,context: Context, message: String) : this(getOrCreateExceptionClass(name), context, message)
    constructor(context: Context, message: String) : this(Root, context, message)

    fun raise(): Nothing {
        throw ExecutionError(this)
    }

    override val objClass: ObjClass = exceptionClass

    override fun toString(): String {
        return "ObjException:${objClass.className}:${context.pos}@${hashCode().encodeToHex()}"
    }

    companion object {

        class ExceptionClass(val name: String,vararg parents: ObjClass) : ObjClass(name, *parents) {
            override suspend fun callOn(context: Context): Obj {
                val message = context.args.getOrNull(0)?.toString() ?: name
                return ObjException(this, context, message)
            }
            override fun toString(): String = "ExceptionClass[$name]@${hashCode().encodeToHex()}"
        }
        val Root = ExceptionClass("Throwable").apply {
            addConst("message", statement {
                (thisObj as ObjException).message.toObj()
            })
        }

        private val op = ProtectedOp()
        private val existingErrorClasses = mutableMapOf<String, ExceptionClass>()


        @OptIn(ExperimentalContracts::class)
        protected fun getOrCreateExceptionClass(name: String): ExceptionClass {
            return op.withLock {
                existingErrorClasses.getOrPut(name) {
                    ExceptionClass(name, Root)
                }
            }
        }

        /**
         * Get [ObjClass] for error class by name if exists.
         */
        @OptIn(ExperimentalContracts::class)
        fun getErrorClass(name: String): ObjClass? = op.withLock {
            existingErrorClasses[name]
        }

        fun addExceptionsToContext(context: Context) {
            context.addConst("Exception", Root)
            existingErrorClasses["Exception"] = Root
            for (name in listOf(
                "NullReferenceException",
                "AssertionFailedException",
                "ClassCastException",
                "IndexOutOfBoundsException",
                "IllegalArgumentException",
                "NoSuchElementException",
                "IllegalAssignmentException",
                "SymbolNotDefinedException",
                "IterationEndException",
                "AccessException",
                "UnknownException",
            )) {
                context.addConst(name, getOrCreateExceptionClass(name))
            }
        }
    }
}

class ObjNullReferenceException(context: Context) : ObjException("NullReferenceException", context, "object is null")

class ObjAssertionFailedException(context: Context, message: String) :
    ObjException("AssertionFailedException", context, message)

class ObjClassCastException(context: Context, message: String) : ObjException("ClassCastException", context, message)
class ObjIndexOutOfBoundsException(context: Context, message: String = "index out of bounds") :
    ObjException("IndexOutOfBoundsException", context, message)

class ObjIllegalArgumentException(context: Context, message: String = "illegal argument") :
    ObjException("IllegalArgumentException", context, message)

class ObjNoSuchElementException(context: Context, message: String = "no such element") :
    ObjException("IllegalArgumentException", context, message)

class ObjIllegalAssignmentException(context: Context, message: String = "illegal assignment") :
    ObjException("NoSuchElementException", context, message)

class ObjSymbolNotDefinedException(context: Context, message: String = "symbol is not defined") :
    ObjException("SymbolNotDefinedException", context, message)

class ObjIterationFinishedException(context: Context) :
    ObjException("IterationEndException", context, "iteration finished")

class ObjAccessException(context: Context, message: String = "access not allowed error") :
    ObjException("AccessException", context, message)

class ObjUnknownException(context: Context, message: String = "access not allowed error") :
    ObjException("UnknownException", context, message)
