package net.sergeych.ling

fun String.toSource(name: String = "eval"): Source = Source(name, this)

sealed class ObjType {
    object Any : ObjType()
    object Int : ObjType()
}


@Suppress("unused")
abstract class Statement(
    val isStaticConst: Boolean = false,
    val isConst: Boolean = false,
    val returnType: ObjType = ObjType.Any
) : Obj() {

    abstract val pos: Pos
    abstract suspend fun execute(context: Context): Obj

    override suspend fun compareTo(context: Context,other: Obj): Int {
        throw UnsupportedOperationException("not comparable")
    }

    override suspend fun callOn(context: Context): Obj {
        return execute(context)
    }

    override fun toString(): String = "Callable@${this.hashCode()}"

}

fun Statement.raise(text: String): Nothing {
    throw ScriptError(pos, text)
}

@Suppress("unused")
fun Statement.require(cond: Boolean, message: () -> String) {
    if (!cond) raise(message())
}

fun statement(pos: Pos, isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend (Context) -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = pos
        override suspend fun execute(context: Context): Obj = f(context)
    }


