package net.sergeych.lyng

class Context(
    val parent: Context?,
    val args: Arguments = Arguments.EMPTY,
    var pos: Pos = Pos.builtIn,
    val thisObj: Obj = ObjVoid
) {
    constructor(
        args: Arguments = Arguments.EMPTY,
        pos: Pos = Pos.builtIn,
    )
            : this(Script.defaultContext, args, pos)

    fun raiseNotImplemented(what: String = "operation"): Nothing = raiseError("$what is not implemented")

    @Suppress("unused")
    fun raiseNPE(): Nothing = raiseError(ObjNullPointerError(this))

    @Suppress("unused")
    fun raiseIndexOutOfBounds(message: String = "Index out of bounds"): Nothing =
        raiseError(ObjIndexOutOfBoundsError(this, message))

    @Suppress("unused")
    fun raiseArgumentError(message: String = "Illegal argument error"): Nothing =
        raiseError(ObjIllegalArgumentError(this, message))

    fun raiseClassCastError(msg: String): Nothing = raiseError(ObjClassCastError(this, msg))

    @Suppress("unused")
    fun raiseSymbolNotFound(name: String): Nothing =
        raiseError(ObjSymbolNotDefinedError(this, "symbol is not defined: $name"))

    fun raiseError(message: String): Nothing {
        throw ExecutionError(ObjError(this, message))
    }

    fun raiseError(obj: ObjError): Nothing {
        throw ExecutionError(obj)
    }

    inline fun <reified T : Obj> requiredArg(index: Int): T {
        if (args.list.size <= index) raiseError("Expected at least ${index + 1} argument, got ${args.list.size}")
        return (args.list[index].value as? T)
            ?: raiseClassCastError("Expected type ${T::class.simpleName}, got ${args.list[index].value::class.simpleName}")
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

    operator fun get(name: String): ObjRecord? =
        objects[name]
            ?: parent?.get(name)

    fun copy(pos: Pos, args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Context =
        Context(this, args, pos, newThisObj ?: thisObj)

    fun copy(args: Arguments = Arguments.EMPTY, newThisObj: Obj? = null): Context =
        Context(this, args, pos, newThisObj ?: thisObj)

    fun copy() = Context(this, args, pos, thisObj)

    fun addItem(
        name: String,
        isMutable: Boolean,
        value: Obj,
        visibility: Compiler.Visibility = Compiler.Visibility.Public
    ): ObjRecord {
        return ObjRecord(value, isMutable, visibility).also { objects.put(name, it) }
    }

    fun getOrCreateNamespace(name: String): ObjClass {
        val ns = objects.getOrPut(name) { ObjRecord(ObjNamespace(name), isMutable = false) }.value
        return ns.objClass
    }

    inline fun addVoidFn(vararg names: String, crossinline fn: suspend Context.() -> Unit) {
        addFn<ObjVoid>(*names) {
            fn(this)
            ObjVoid
        }
    }

    inline fun <reified T : Obj> addFn(vararg names: String, crossinline fn: suspend Context.() -> T) {
        val newFn = object : Statement() {
            override val pos: Pos = Pos.builtIn

            override suspend fun execute(context: Context): Obj = context.fn()

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
        Compiler().compile(code.toSource()).execute(this)

    fun containsLocal(name: String): Boolean = name in objects


}
