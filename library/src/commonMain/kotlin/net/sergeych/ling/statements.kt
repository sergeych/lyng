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

class LogicalAndStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {

        val l = left.execute(context).let {
            (it as? ObjBool) ?: raise("left operand is not boolean: $it")
        }
        val r = right.execute(context).let {
            (it as? ObjBool) ?: raise("right operand is not boolean: $it")
        }
        return ObjBool(l.value && r.value)
    }
}

class LogicalOrStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {

        val l = left.execute(context).let {
            (it as? ObjBool) ?: raise("left operand is not boolean: $it")
        }
        val r = right.execute(context).let {
            (it as? ObjBool) ?: raise("right operand is not boolean: $it")
        }
        return ObjBool(l.value || r.value)
    }
}

class PlusStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        // todo: implement also classes with 'plus' operator
        val l = left.execute(context)

        if (l is ObjString)
            return ObjString(l.toString() + right.execute(context).asStr)

        if (l !is Numeric)
            raise("left operand is not number: $l")

        val r = right.execute(context)
        if (r !is Numeric)
            raise("right operand is not boolean: $r")

        return if (l is ObjInt && r is ObjInt)
            ObjInt(l.longValue + r.longValue)
        else
            ObjReal(l.doubleValue + r.doubleValue)
    }
}

class MinusStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        // todo: implement also classes with 'minus' operator
        val l = left.execute(context)
        if (l !is Numeric)
            raise("left operand is not number: $l")

        val r = right.execute(context)
        if (r !is Numeric)
            raise("right operand is not number: $r")

        return if (l is ObjInt && r is ObjInt)
            ObjInt(l.longValue - r.longValue)
        else
            ObjReal(l.doubleValue - r.doubleValue)
    }
}

class MulStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val l = left.execute(context)
        if (l !is Numeric)
            raise("left operand is not number: $l")

        val r = right.execute(context)
        if (r !is Numeric)
            raise("right operand is not number: $r")

        return if (l is ObjInt && r is ObjInt)
            ObjInt(l.longValue * r.longValue)
        else
            ObjReal(l.doubleValue * r.doubleValue)
    }
}

class DivStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val l = left.execute(context)
        if (l !is Numeric)
            raise("left operand is not number: $l")

        val r = right.execute(context)
        if (r !is Numeric)
            raise("right operand is not number: $r")

        return if (l is ObjInt && r is ObjInt)
            ObjInt(l.longValue / r.longValue)
        else
            ObjReal(l.doubleValue / r.doubleValue)
    }
}

class ModStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val l = left.execute(context)
        if (l !is Numeric)
            raise("left operand is not number: $l")

        val r = right.execute(context)
        if (r !is Numeric)
            raise("right operand is not number: $r")

        return if (l is ObjInt && r is ObjInt)
            ObjInt(l.longValue % r.longValue)
        else
            ObjReal(l.doubleValue % r.doubleValue)
    }
}



class AssignStatement(override val pos: Pos, val name: String, val value: Statement) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val variable = context[name] ?: raise("can't assign: variable does not exist: $name")
        if (!variable.isMutable)
            throw ScriptError(pos, "can't reassign val $name")
        variable.value = value.execute(context)
        return ObjVoid
    }
}

