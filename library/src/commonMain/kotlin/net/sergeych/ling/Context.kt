package net.sergeych.ling

class Context(
    val parent: Context? = Script.defaultContext.copy(),
    val args: Arguments = Arguments.EMPTY
) {

    private val objects = mutableMapOf<String, StoredObj>()

    operator fun get(name: String): StoredObj? =
        objects[name]
            ?: parent?.get(name)

    fun copy(args: Arguments = Arguments.EMPTY): Context = Context(this, args)

    fun addItem(name: String, isMutable: Boolean, value: Obj?) {
        objects.put(name, StoredObj(name, value, isMutable))
    }

    fun getOrCreateNamespace(name: String) =
        (objects.getOrPut(name) { StoredObj(name, ObjNamespace(name,copy()), isMutable = false) }.value as ObjNamespace)
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

    inline fun <reified T> addConst(value: T,vararg names: String) {
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

    companion object {
        operator fun invoke() = Context()
    }

}
