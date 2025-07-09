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
    val getter: suspend (Scope) -> ObjRecord,
    val setterOrNull: (suspend (Scope, Obj) -> Unit)?
) {
    /**
     * Simplified constructor for immutable stores.
     */
    constructor(getter: suspend (Scope) -> ObjRecord) : this(getter, null)

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
    ): T = invokeInstanceMethod(scope, name, args) as T

    open suspend fun invokeInstanceMethod(
        scope: Scope,
        name: String,
        args: Arguments = Arguments.EMPTY
    ): Obj =
        // note that getInstanceMember traverses the hierarchy
        objClass.getInstanceMember(scope.pos, name).value.invoke(scope, this, args)

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

    open val asStr: ObjString by lazy {
        if (this is ObjString) this else ObjString(this.toString())
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

    open suspend fun assign(scope: Scope, other: Obj): Obj? = null

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


    companion object {

        val rootObjectType = ObjClass("Obj").apply {
                addFn("toString") {
                    thisObj.asStr
                }
                addFn("contains") {
                    ObjBool(thisObj.contains(this, args.firstAndOnly()))
                }
                // utilities
                addFn("let") {
                    args.firstAndOnly().callOn(copy(Arguments(thisObj)))
                }
                addFn("apply") {
                    val newContext = ( thisObj as? ObjInstance)?.instanceScope ?: this
                    args.firstAndOnly()
                        .callOn(newContext)
                    thisObj
                }
                addFn("also") {
                    args.firstAndOnly().callOn(copy(Arguments(thisObj)))
                    thisObj
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

    override suspend fun invokeInstanceMethod(scope: Scope, name: String, args: Arguments): Obj {
        scope.raiseNPE()
    }

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

open class ObjException(exceptionClass: ExceptionClass, val scope: Scope, val message: String) : Obj() {
    constructor(name: String, scope: Scope, message: String) : this(
        getOrCreateExceptionClass(name),
        scope,
        message
    )

    constructor(scope: Scope, message: String) : this(Root, scope, message)

    fun raise(): Nothing {
        throw ExecutionError(this)
    }

    override val objClass: ObjClass = exceptionClass

    override fun toString(): String {
        return "ObjException:${objClass.className}:${scope.pos}@${hashCode().encodeToHex()}"
    }

    companion object {

        class ExceptionClass(val name: String, vararg parents: ObjClass) : ObjClass(name, *parents) {
            override suspend fun callOn(scope: Scope): Obj {
                val message = scope.args.getOrNull(0)?.toString() ?: name
                return ObjException(this, scope, message)
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

        fun addExceptionsToContext(scope: Scope) {
            scope.addConst("Exception", Root)
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
                scope.addConst(name, getOrCreateExceptionClass(name))
            }
        }
    }
}

class ObjNullReferenceException(scope: Scope) : ObjException("NullReferenceException", scope, "object is null")

class ObjAssertionFailedException(scope: Scope, message: String) :
    ObjException("AssertionFailedException", scope, message)

class ObjClassCastException(scope: Scope, message: String) : ObjException("ClassCastException", scope, message)
class ObjIndexOutOfBoundsException(scope: Scope, message: String = "index out of bounds") :
    ObjException("IndexOutOfBoundsException", scope, message)

class ObjIllegalArgumentException(scope: Scope, message: String = "illegal argument") :
    ObjException("IllegalArgumentException", scope, message)

@Suppress("unused")
class ObjNoSuchElementException(scope: Scope, message: String = "no such element") :
    ObjException("IllegalArgumentException", scope, message)

class ObjIllegalAssignmentException(scope: Scope, message: String = "illegal assignment") :
    ObjException("NoSuchElementException", scope, message)

class ObjSymbolNotDefinedException(scope: Scope, message: String = "symbol is not defined") :
    ObjException("SymbolNotDefinedException", scope, message)

class ObjIterationFinishedException(scope: Scope) :
    ObjException("IterationEndException", scope, "iteration finished")

class ObjAccessException(scope: Scope, message: String = "access not allowed error") :
    ObjException("AccessException", scope, message)

class ObjUnknownException(scope: Scope, message: String = "access not allowed error") :
    ObjException("UnknownException", scope, message)

class ObjIllegalOperationException(scope: Scope, message: String = "Operation is illegal") :
    ObjException("IllegalOperationException", scope, message)
