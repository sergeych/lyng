package net.sergeych.lyng

open class Scope(
    val parent: Scope?,
    val args: Arguments = Arguments.EMPTY,
    var pos: Pos = Pos.builtIn,
    var thisObj: Obj = ObjVoid,
    var skipScopeCreation: Boolean = false,
) {
    constructor(
        args: Arguments = Arguments.EMPTY,
        pos: Pos = Pos.builtIn,
    )
            : this(Script.defaultScope, args, pos)

    fun raiseNotImplemented(what: String = "operation"): Nothing = raiseError("$what is not implemented")

    @Suppress("unused")
    fun raiseNPE(): Nothing = raiseError(ObjNullReferenceException(this))

    @Suppress("unused")
    fun raiseIndexOutOfBounds(message: String = "Index out of bounds"): Nothing =
        raiseError(ObjIndexOutOfBoundsException(this, message))

    @Suppress("unused")
    fun raiseIllegalArgument(message: String = "Illegal argument error"): Nothing =
        raiseError(ObjIllegalArgumentException(this, message))

    @Suppress("unused")
    fun raiseNoSuchElement(message: String = "No such element"): Nothing =
        raiseError(ObjIllegalArgumentException(this, message))

    fun raiseClassCastError(msg: String): Nothing = raiseError(ObjClassCastException(this, msg))

    @Suppress("unused")
    fun raiseSymbolNotFound(name: String): Nothing =
        raiseError(ObjSymbolNotDefinedException(this, "symbol is not defined: $name"))

    fun raiseError(message: String): Nothing {
        throw ExecutionError(ObjException(this, message))
    }

    fun raiseError(obj: ObjException): Nothing {
        throw ExecutionError(obj)
    }

    inline fun <reified T : Obj> requiredArg(index: Int): T {
        if (args.list.size <= index) raiseError("Expected at least ${index + 1} argument, got ${args.list.size}")
        return (args.list[index] as? T)
            ?: raiseClassCastError("Expected type ${T::class.simpleName}, got ${args.list[index]::class.simpleName}")
    }

    inline fun <reified T : Obj> requireOnlyArg(): T {
        if (args.list.size != 1) raiseError("Expected exactly 1 argument, got ${args.list.size}")
        return requiredArg(0)
    }

    @Suppress("unused")
    fun requireExactCount(count: Int) {
        if (args.list.size != count) {
            raiseError("Expected exactly $count arguments, got ${args.list.size}")
        }
    }

    inline fun <reified T : Obj> thisAs(): T = (thisObj as? T)
        ?: raiseClassCastError("Cannot cast ${thisObj.objClass.className} to ${T::class.simpleName}")

    internal val objects = mutableMapOf<String, ObjRecord>()

    open operator fun get(name: String): ObjRecord? =
        if (name == "this") thisObj.asReadonly
        else {
            objects[name]
                ?: parent?.get(name)
                ?: thisObj.objClass.getInstanceMemberOrNull(name)
        }

    fun copy(pos: Pos, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj)

    fun copy(args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Scope =
        Scope(this, args, pos, newThisObj ?: thisObj)

    fun copy() = Scope(this, args, pos, thisObj)

    fun addItem(
        name: String,
        isMutable: Boolean,
        value: Obj,
        visibility: Visibility = Visibility.Public
    ): ObjRecord {
        return ObjRecord(value, isMutable, visibility).also { objects[name] = it }
    }

    fun getOrCreateNamespace(name: String): ObjClass {
        val ns = objects.getOrPut(name) { ObjRecord(ObjNamespace(name), isMutable = false) }.value
        return ns.objClass
    }

    inline fun addVoidFn(vararg names: String, crossinline fn: suspend Scope.() -> Unit) {
        addFn<ObjVoid>(*names) {
            fn(this)
            ObjVoid
        }
    }

    inline fun <reified T : Obj> addFn(vararg names: String, crossinline fn: suspend Scope.() -> T) {
        val newFn = object : Statement() {
            override val pos: Pos = Pos.builtIn

            override suspend fun execute(scope: Scope): Obj = scope.fn()

        }
        for (name in names) {
            addItem(
                name,
                false,
                newFn
            )
        }
    }

    fun addConst(name: String, value: Obj) = addItem(name, false, value)

    suspend fun eval(code: String): Obj =
        Compiler.compile(code.toSource()).execute(this)

    fun containsLocal(name: String): Boolean = name in objects

}
