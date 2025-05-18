package net.sergeych.ling

class Context(
    val parent: Context? = null,
    val args: Arguments = Arguments.EMPTY
) {

    data class Item(
        val name: String,
        var value: Obj?,
        val isMutable: Boolean = false
    )

    private val objects = mutableMapOf<String, Item>()

    operator fun get(name: String): Item? = objects[name] ?: parent?.get(name)

    fun copy(args: Arguments = Arguments.EMPTY): Context = Context(this, args)

    fun addItem(name: String, isMutable: Boolean, value: Obj?) {
        objects.put(name, Item(name, value, isMutable))
    }

    fun getOrCreateNamespace(name: String) =
        (objects.getOrPut(name) { Item(name, ObjNamespace(name,copy()), isMutable = false) }.value as ObjNamespace)
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

    companion object {
        operator fun invoke() = Context()
    }

}
