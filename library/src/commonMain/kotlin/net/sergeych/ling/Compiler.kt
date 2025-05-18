package net.sergeych.ling

//sealed class ObjType(name: String, val defaultValue: Obj? = null) {
//
//    class Str : ObjType("string", ObjString(""))
//    class Int : ObjType("real", ObjReal(0.0))
//
//}
//
///**
// * Descriptor for whatever object could be used as argument, return value,
// * field, etc.
// */
//data class ObjDescriptor(
//    val type: ObjType,
//    val mutable: Boolean,
//)
//
//data class MethodDescriptor(
//    val args: Array<ObjDescriptor>,
//    val result: ObjDescriptor
//)

/*
Meta context contains map of symbols.

Each symbol can be:

- a var
- a const
- a function
- a type alias
- a class definition

Each have in common only its name.

Var has: type, value. Const is same as var but value is fixed. Function has args and return value,
type alias has target type name. So we have to have something that denotes a _type_

 */


//data class MetaContext(val symbols: MutableMap<String, > = mutableMapOf()) {
//
//}


class Compiler {

    fun compile(source: Source): Script {
        val tokens = parseLing(source).listIterator()
        val start = source.startPos
        // at this level: "global" context: just script to execute or new function
        // declaration forming closures
        val statements = mutableListOf<Statement>()
        while (parseStatement(tokens)?.also {
                statements += it
            } != null) {/**/
        }
        return Script(start, statements)
    }

    private fun parseStatement(tokens: ListIterator<Token>): Statement? {
        val t = tokens.next()
        return when (t.type) {
            Token.Type.ID -> {
                // could be keyword, assignment or just the expression
                val next = tokens.next()
                if (next.type == Token.Type.EQ) {
                    // this _is_ assignment statement
                    AssignStatement(
                        t.pos, t.value,
                        parseExpression(tokens) ?: throw ScriptError(
                            t.pos,
                            "Expecting expression for assignment operator"
                        )
                    )
                }
                // not assignment, maybe keyword statement:
                // get back the token which is not '=':
                tokens.previous()
                // try keyword statement
                parseKeywordStatement(t, tokens)
                    ?: run {
                        tokens.previous()
                        parseExpression(tokens)
                    }
            }

            Token.Type.EOF -> null

            else -> {
                // could be expression
                tokens.previous()
                parseExpression(tokens)
            }
        }
    }

    private fun parseExpression(tokens: ListIterator<Token>, level: Int = 0): Statement? {
        if (level == lastLevel)
            return parseTerm(tokens)
        var lvalue = parseExpression(tokens, level + 1)
        if (lvalue == null) return null

        while (true) {

            val opToken = tokens.next()
            val op = byLevel[level][opToken.value]
            if (op == null) {
                tokens.previous()
                break
            }

            val rvalue = parseExpression(tokens, level + 1)
                ?: throw ScriptError(opToken.pos, "Expecting expression")

            lvalue = op.generate(opToken.pos, lvalue!!, rvalue)
        }
        return lvalue
    }

    fun parseTerm(tokens: ListIterator<Token>): Statement? {
        // call op
        // index op
        // unary op
        // parenthesis
        // number or string
        val t = tokens.next()
        // todoL var?
        return when (t.type) {
            Token.Type.ID -> {
                parseVarAccess(t, tokens)
            }
                // todoL: check if it's a function call
                // todoL: check if it's a field access
                // todoL: check if it's a var
                // todoL: check if it's a const
                // todoL: check if it's a type

//            "+" -> statement { parseNumber(true,tokens) }??????
//            "-" -> statement { parseNumber(false,tokens) }
//            "~" -> statement(t.pos) { ObjInt( parseLong(tokens)) }

            Token.Type.PLUS -> {
                val n = parseNumber(true, tokens)
                statement(t.pos) { n }
            }

            Token.Type.MINUS -> {
                val n = parseNumber(false, tokens)
                statement(t.pos) { n }
            }

            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                tokens.previous()
                val n = parseNumber(true, tokens)
                statement(t.pos) { n }
            }

            else -> null
        }

    }

    fun parseVarAccess(id: Token, tokens: ListIterator<Token>,path: List<String> = emptyList()): Statement {
        val nt = tokens.next()

        fun resolve(context: Context): Context {
            var targetContext = context
            for( n in path) {
                val x = targetContext[n] ?: throw ScriptError(id.pos, "undefined symbol: $n")
                (x.value as? ObjNamespace )?.let { targetContext = it.context }
                    ?: throw ScriptError(id.pos, "Invalid symbolic path (wrong type of ${x.name}: ${x.value}")
            }
            return targetContext
        }
        return when(nt.type) {
            Token.Type.DOT -> {
                // selector
                val t = tokens.next()
                if( t.type== Token.Type.ID) {
                    parseVarAccess(t,tokens,path+id.value)
                }
                else
                    throw ScriptError(t.pos,"Expected identifier after '.'")
            }
            Token.Type.LPAREN -> {
                // Load arg list
                val args = mutableListOf<Statement>()
                do {
                    val t = tokens.next()
                    when(t.type) {
                        Token.Type.RPAREN, Token.Type.COMMA -> {}
                        else -> {
                            tokens.previous()
                            parseExpression(tokens)?.let { args += it }
                                ?: throw ScriptError(t.pos, "Expecting arguments list")
                        }
                    }
                } while (t.type != Token.Type.RPAREN)
                statement(id.pos) { context ->
                    val v = resolve(context).get(id.value) ?: throw ScriptError(id.pos, "Undefined variable: $id")
                    (v.value as? Statement)?.execute(context.copy(Arguments(args.map { it.execute(context) })))
                        ?: throw ScriptError(id.pos, "Variable $id is not callable ($id)")
                }
            }
            Token.Type.LBRACKET -> {
                TODO("indexing")
            }
            else -> {
                // just access the var
                tokens.previous()
                statement(id.pos) {
                    val v = resolve(it).get(id.value) ?: throw ScriptError(id.pos, "Undefined variable: $id")
                    v.value ?: throw ScriptError(id.pos, "Variable $id is not initialized")
                }
            }
        }
    }


    fun parseNumber(isPlus: Boolean, tokens: ListIterator<Token>): Obj {
        val t = tokens.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.HEX -> {
                val n = t.value.toLong(if (t.type == Token.Type.HEX) 16 else 10)
                if (isPlus) ObjInt(n) else ObjInt(-n)
            }

            Token.Type.REAL -> {
                val d = t.value.toDouble()
                if (isPlus) ObjReal(d) else ObjReal(-d)
            }

            else -> {
                throw ScriptError(t.pos, "expected number")
            }
        }
    }

    /**
     * Parse keyword-starting statenment.
     * @return parsed statement or null if, for example. [id] is not among keywords
     */
    @Suppress("UNUSED_PARAMETER")
    private fun parseKeywordStatement(id: Token, tokens: ListIterator<Token>): Statement? {
        return null
    }

//    fun parseStatement(parser: Parser): Statement? =
//        parser.withToken {
//            if (tokens.isEmpty()) null
//            else {
//                when (val token = tokens[0]) {
//                    else -> {
//                        rollback()
//                        null
//                    }
//                }
//            }
//        }

    companion object {
        data class Operator(
            val name: String,
            val priority: Int, val arity: Int,
            val generate: (Pos, Statement, Statement) -> Statement
        )

        val allOps = listOf(
//            Operator("||", 0, 2) { pos, a, b -> LogicalOrStatement(pos, a, b) })
            Operator("&&", 1, 2) { pos, a, b ->
                LogicalAndStatement(pos, a, b)
            },
            // bitwise or 2
            // bitwise and 3
            // equality/ne 4
            // relational <=,... 5
            // shuttle <=> 6
            // bitshhifts 7
            // + - : 7
            Operator("+", 7, 2) { pos, a, b ->
                PlusStatement(pos, a, b)
            },
            Operator("-", 7, 2) { pos, a, b ->
                MinusStatement(pos, a, b)
            },
            // * / %: 8
        )
        val lastLevel = 9
        val byLevel: List<Map<String, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }
                .map { it.name to it }.toMap()
        }

        fun compile(code: String): Script = Compiler().compile(Source("<eval>", code))
    }
}

suspend fun eval(code: String) = Compiler.compile(code).execute()