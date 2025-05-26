package net.sergeych.ling

class Context(
    val parent: Context?,
    val args: Arguments = Arguments.EMPTY,
    var pos: Pos = Pos.builtIn
) {
    constructor(
        args: Arguments = Arguments.EMPTY,
        pos: Pos = Pos.builtIn
    )
            : this(Script.defaultContext, args, pos)

    fun raiseNotImplemented(): Nothing = raiseError("operation not implemented")

    fun raiseNPE(): Nothing = raiseError(ObjNullPointerError(this))

    fun raiseError(message: String): Nothing {
        throw ExecutionError(ObjError(this, message))
    }

    fun raiseError(obj: ObjError): Nothing {
        throw ExecutionError(obj)
    }

    private val objects = mutableMapOf<String, StoredObj>()

    operator fun get(name: String): StoredObj? =
        objects[name]
            ?: parent?.get(name)

    fun copy(pos: Pos, args: Arguments = Arguments.EMPTY): Context = Context(this, args, pos)

    fun addItem(name: String, isMutable: Boolean, value: Obj?) {
        objects.put(name, StoredObj(value, isMutable))
    }

    fun getOrCreateNamespace(name: String) =
        (objects.getOrPut(name) {
            StoredObj(
                ObjNamespace(name, copy(pos)),
                isMutable = false
            )
        }.value as ObjNamespace)
            .context

    inline fun <reified T> addFn(vararg names: String, crossinline fn: suspend Context.() -> T) {
        val newFn = object : Statement() {
            override val pos: Pos = Pos.builtIn

            override suspend fun execute(context: Context): Obj {
                return try {
                    from(context.fn())

                } catch (e: Exception) {
                    raise(e.message ?: "unexpected error")
                }
            }
        }
        for (name in names) {
            addItem(
                name,
                false,
                newFn
            )
        }
    }

    inline fun <reified T> addConst(value: T, vararg names: String) {
        val obj = Obj.from(value)
        for (name in names) {
            addItem(
                name,
                false,
                obj
            )
        }
    }

    suspend fun eval(code: String): Obj =
        Compiler().compile(code.toSource()).execute(this)

    fun containsLocal(name: String): Boolean = name in objects


}
