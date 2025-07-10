package net.sergeych.lyng

fun String.toSource(name: String = "eval"): Source = Source(name, this)

sealed class ObjType {
    object Any : ObjType()
    object Int : ObjType()
}


@Suppress("unused")
abstract class Statement(
    val isStaticConst: Boolean = false,
    override val isConst: Boolean = false,
    val returnType: ObjType = ObjType.Any
) : Obj() {

    override val objClass: ObjClass = type

    abstract val pos: Pos
    abstract suspend fun execute(scope: Scope): Obj

    override suspend fun compareTo(scope: Scope, other: Obj): Int {
        throw UnsupportedOperationException("not comparable")
    }

    override suspend fun callOn(scope: Scope): Obj {
        return execute(scope)
    }

    override fun toString(): String = "Callable@${this.hashCode()}"

    companion object {
        val type = ObjClass("Callable")
    }

    suspend fun call(scope: Scope, vararg args: Obj) = execute(scope.copy(args =  Arguments(*args)))

}

fun Statement.raise(text: String): Nothing {
    throw ScriptError(pos, text)
}

@Suppress("unused")
fun Statement.require(cond: Boolean, message: () -> String) {
    if (!cond) raise(message())
}

fun statement(pos: Pos, isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend (Scope) -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = pos
        override suspend fun execute(scope: Scope): Obj = f(scope)
    }

fun statement(isStaticConst: Boolean = false, isConst: Boolean = false, f: suspend Scope.() -> Obj): Statement =
    object : Statement(isStaticConst, isConst) {
        override val pos: Pos = Pos.builtIn
        override suspend fun execute(scope: Scope): Obj = f(scope)
    }


