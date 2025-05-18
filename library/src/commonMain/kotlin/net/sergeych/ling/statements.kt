package net.sergeych.ling

fun String.toSource(name: String = "eval"): Source = Source(name, this)


abstract class Statement : Obj() {
    abstract val pos: Pos
    abstract suspend fun execute(context: Context): Obj
}

fun Statement.raise(text: String): Nothing {
    throw ScriptError(pos, text)
}

fun Statement.require(cond: Boolean, message: () -> String) {
    if (!cond) raise(message())
}

fun statement(pos: Pos, f: suspend (Context) -> Obj): Statement = object : Statement() {
    override val pos: Pos = pos
    override suspend fun execute(context: Context): Obj = f(context)
}

class IfStatement(
    override val pos: Pos,
    val cond: Statement, val ifTrue: Statement, val ifFalse: Statement?
) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val c = cond.execute(context)
        if (c !is ObjBool)
            raise("if: condition must me boolean, got: $c")

        return if (c.value) ifTrue.execute(context) else ifFalse?.execute(context) ?: ObjVoid
    }
}

class LogicalAndStatement(
    override val pos: Pos,
    val left: Statement, val right: Statement
) : Statement() {
    override suspend fun execute(context: Context): Obj {

        val l = left.execute(context).let {
            (it as? ObjBool) ?: raise("logical and: left operand is not boolean: $it")
        }
        val r = right.execute(context).let {
            (it as? ObjBool) ?: raise("logical and: right operand is not boolean: $it")
        }
        return ObjBool(l.value && r.value)
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
            return ObjString(l.toString() + right.execute(context).toString())

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

class AssignStatement(override val pos: Pos, val name: String, val value: Statement) : Statement() {
    override suspend fun execute(context: Context): Obj {
        val variable = context[name] ?: raise("can't assign: variable does not exist: $name")
        variable.value = value.execute(context)
        return ObjVoid
    }
}

