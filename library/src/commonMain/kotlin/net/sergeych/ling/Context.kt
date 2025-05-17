package net.sergeych.ling

import kotlin.math.PI

class Context(
    val callerPos: Pos,
    val parent: Context? = null,
    val args: Arguments = Arguments.EMPTY
) {

    data class Item(
        val name: String, var value: Obj?,
        val isMutable: Boolean = false
    )

    private val objects = mutableMapOf<String, Item>()

    operator fun get(name: String): Item? = objects[name] ?: parent?.get(name)

    fun copy(from: Pos, args: Arguments = Arguments.EMPTY): Context = Context(from, this, args)

    fun addFn(name: String, fn: suspend Context.() -> Obj) = objects.put(name, Item(name,
        object : Statement() {
            override val pos: Pos = Pos.builtIn

            override suspend fun execute(context: Context): Obj {
                return try {
                    context.fn()
                } catch (e: Exception) {
                    raise(e.message ?: "unexpected error")
                }
            }

        })
    )

}

val basicContext = Context(Pos.builtIn).apply {
    addFn("println") {
        require(args.size == 1)
        println(args[0].execute(this).asStr.value)
        Void
    }
    addFn("π") { ObjReal(PI) }
}