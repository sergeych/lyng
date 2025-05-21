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


class CompilerContext(tokens: List<Token>) : ListIterator<Token> by tokens.listIterator() {
    val labels = mutableSetOf<String>()

    fun ensureLabelIsValid(pos: Pos, label: String) {
        if (label !in labels)
            throw ScriptError(pos, "Undefined label '$label'")
    }
}


class Compiler {

    fun compile(source: Source): Script {
        return parseScript(source.startPos, CompilerContext(parseLing(source)))
    }

    private fun parseScript(start: Pos, tokens: CompilerContext): Script {
        val statements = mutableListOf<Statement>()
        while (parseStatement(tokens)?.also {
                statements += it
            } != null) {/**/
        }
        return Script(start, statements)
    }

    private fun parseStatement(tokens: CompilerContext): Statement? {
        while (true) {
            val t = tokens.next()
            return when (t.type) {
                Token.Type.ID -> {
                    // could be keyword, assignment or just the expression
                    val next = tokens.next()
                    if (next.type == Token.Type.ASSIGN) {
                        // this _is_ assignment statement
                        return AssignStatement(
                            t.pos, t.value,
                            parseStatement(tokens) ?: throw ScriptError(
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
                Token.Type.PLUS2, Token.Type.MINUS2 -> {
                    tokens.previous()
                    parseExpression(tokens)
                }

                Token.Type.LABEL -> continue
                Token.Type.SINLGE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> continue

                Token.Type.NEWLINE -> continue

                Token.Type.SEMICOLON -> continue

                Token.Type.LBRACE -> {
                    tokens.previous()
                    parseBlock(tokens)
                }

                Token.Type.RBRACE -> {
                    tokens.previous()
                    return null
                }

                Token.Type.EOF -> null

                else -> {
                    // could be expression
                    tokens.previous()
                    parseExpression(tokens)
                }
            }
        }
    }

    private fun parseExpression(tokens: CompilerContext, level: Int = 0): Statement? {
        if (level == lastLevel)
            return parseTerm(tokens)
        var lvalue = parseExpression(tokens, level + 1)
        if (lvalue == null) return null

        while (true) {

            val opToken = tokens.next()
            val op = byLevel[level][opToken.type]
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

    fun parseTerm(tokens: CompilerContext): Statement? {
        // call op
        // index op
        // unary op
        // parenthesis
        // number or string
        val t = tokens.next()
        // todoL var?
        return when (t.type) {
            Token.Type.ID -> {
                when (t.value) {
                    "void" -> statement(t.pos, true) { ObjVoid }
                    "null" -> statement(t.pos, true) { ObjNull }
                    "true" -> statement(t.pos, true) { ObjBool(true) }
                    "false" -> statement(t.pos, true) { ObjBool(false) }
                    else -> parseVarAccess(t, tokens)
                }
            }

            Token.Type.STRING -> statement(t.pos, true) { ObjString(t.value) }

            Token.Type.LPAREN -> {
                // ( subexpr )
                parseExpression(tokens)?.also {
                    val tl = tokens.next()
                    if (tl.type != Token.Type.RPAREN)
                        throw ScriptError(t.pos, "unbalanced parenthesis: no ')' for it")
                }
            }

            Token.Type.PLUS -> {
                val n = parseNumber(true, tokens)
                statement(t.pos, true) { n }
            }

            Token.Type.MINUS -> {
                val n = parseNumber(false, tokens)
                statement(t.pos, true) { n }
            }

            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                tokens.previous()
                val n = parseNumber(true, tokens)
                statement(t.pos, true) { n }
            }

            else -> null
        }

    }

    fun parseVarAccess(id: Token, tokens: CompilerContext, path: List<String> = emptyList()): Statement {
        val nt = tokens.next()

        fun resolve(context: Context): Context {
            var targetContext = context
            for (n in path) {
                val x = targetContext[n] ?: throw ScriptError(id.pos, "undefined symbol: $n")
                (x.value as? ObjNamespace)?.let { targetContext = it.context }
                    ?: throw ScriptError(id.pos, "Invalid symbolic path (wrong type of ${x.name}: ${x.value}")
            }
            return targetContext
        }
        return when (nt.type) {
            Token.Type.DOT -> {
                // selector
                val t = tokens.next()
                if (t.type == Token.Type.ID) {
                    parseVarAccess(t, tokens, path + id.value)
                } else
                    throw ScriptError(t.pos, "Expected identifier after '.'")
            }

            Token.Type.PLUS2 -> {
                statement(id.pos) { context ->
                    context.pos = id.pos
                    val v = resolve(context).get(id.value)
                        ?: throw ScriptError(id.pos, "Undefined symbol: ${id.value}")
                    v.value?.getAndIncrement(context)
                        ?: context.raiseNPE()
                }
            }

            Token.Type.MINUS2 -> {
                statement(id.pos) { context ->
                    context.pos = id.pos
                    val v = resolve(context).get(id.value)
                        ?: throw ScriptError(id.pos, "Undefined symbol: ${id.value}")
                    v.value?.getAndDecrement(context)
                        ?: context.raiseNPE()
                }
            }
            Token.Type.LPAREN -> {
                // function call
                // Load arg list
                val args = mutableListOf<Arguments.Info>()
                do {
                    val t = tokens.next()
                    when (t.type) {
                        Token.Type.RPAREN, Token.Type.COMMA -> {}
                        else -> {
                            tokens.previous()
                            parseStatement(tokens)?.let { args += Arguments.Info(it, t.pos) }
                                ?: throw ScriptError(t.pos, "Expecting arguments list")
                        }
                    }
                } while (t.type != Token.Type.RPAREN)

                statement(id.pos) { context ->
                    val v =
                        resolve(context).get(id.value) ?: throw ScriptError(id.pos, "Undefined function: ${id.value}")
                    (v.value as? Statement)?.execute(
                        context.copy(
                            id.pos,
                            Arguments(
                                nt.pos,
                                args.map { Arguments.Info((it.value as Statement).execute(context), it.pos) }
                            ),
                        )
                    )
                        ?: throw ScriptError(id.pos, "Variable $id is not callable ($id)")
                }
            }

            Token.Type.LBRACKET -> {
                TODO("indexing")
            }

            else -> {
                // just access the var
                tokens.previous()
//                val t = tokens.next()
//                tokens.previous()
//                println(t)
//                if( path.isEmpty() ) {
//                     statement?
//                    tokens.previous()
//                    parseStatement(tokens) ?: throw ScriptError(id.pos, "Expecting expression/statement")
//                } else
                statement(id.pos) {
                    val v = resolve(it).get(id.value) ?: throw ScriptError(id.pos, "Undefined variable: ${id.value}")
                    v.value ?: throw ScriptError(id.pos, "Variable $id is not initialized")
                }
            }
        }
    }


    fun parseNumber(isPlus: Boolean, tokens: CompilerContext): Obj {
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
    private fun parseKeywordStatement(id: Token, cc: CompilerContext): Statement? = when (id.value) {
        "val" -> parseVarDeclaration(id.value, false, cc)
        "var" -> parseVarDeclaration(id.value, true, cc)
        "while" -> parseWhileStatement(cc)
        "break" -> parseBreakStatement(id.pos, cc)
        "continue" -> parseContinueStatement(id.pos, cc)
        "fn", "fun" -> parseFunctionDeclaration(cc)
        "if" -> parseIfStatement(cc)
        else -> null
    }

    fun getLabel(cc: CompilerContext, maxDepth: Int = 2): String? {
        var cnt = 0
        var found: String? = null
        while (cc.hasPrevious() && cnt < maxDepth) {
            val t = cc.previous()
            cnt++
            if (t.type == Token.Type.LABEL) {
                found = t.value
                break
            }
        }
        while (cnt-- > 0) cc.next()
        return found
    }

    private fun parseWhileStatement(cc: CompilerContext): Statement {
        val label = getLabel(cc)?.also { cc.labels += it }
        val start = ensureLparen(cc)
        val condition = parseExpression(cc) ?: throw ScriptError(start, "Bad while statement: expected expression")
        ensureRparen(cc)

        val body = parseStatement(cc) ?: throw ScriptError(start, "Bad while statement: expected statement")
        label?.also { cc.labels -= it }

        return statement(body.pos) {
            var result: Obj = ObjVoid
            while (condition.execute(it).toBool()) {
                try {
                    result = body.execute(it)
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) continue
                        else {
                            result = lbe.result
                            break
                        }
                    } else
                        throw lbe
                }
            }
            result
        }
    }

    private fun parseBreakStatement(start: Pos, cc: CompilerContext): Statement {
        var t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }?.also {
            // check that label is defined
            cc.ensureLabelIsValid(start, it)
        }

        // expression?
        t = cc.next()
        cc.previous()
        val resultExpr = if (t.pos.line == start.line && (!t.isComment &&
                    t.type != Token.Type.SEMICOLON &&
                    t.type != Token.Type.NEWLINE)
        ) {
            // we have something on this line, could be expression
            parseStatement(cc)
        } else null

        return statement(start) {
            val returnValue = resultExpr?.execute(it)// ?: ObjVoid
            throw LoopBreakContinueException(
                doContinue = false,
                label = label,
                result = returnValue ?: ObjVoid
            )
        }
    }

    private fun parseContinueStatement(start: Pos, cc: CompilerContext): Statement {
        val t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }?.also {
            // check that label is defined
            cc.ensureLabelIsValid(start, it)
        }

        return statement(start) {
            throw LoopBreakContinueException(
                doContinue = true,
                label = label,
            )
        }
    }

    private fun ensureRparen(tokens: CompilerContext): Pos {
        val t = tokens.next()
        if (t.type != Token.Type.RPAREN)
            throw ScriptError(t.pos, "expected ')'")
        return t.pos
    }

    private fun ensureLparen(tokens: CompilerContext): Pos {
        val t = tokens.next()
        if (t.type != Token.Type.LPAREN)
            throw ScriptError(t.pos, "expected '('")
        return t.pos
    }

    private fun parseIfStatement(tokens: CompilerContext): Statement {
        val start = ensureLparen(tokens)

        val condition = parseExpression(tokens)
            ?: throw ScriptError(start, "Bad if statement: expected expression")

        val pos = ensureRparen(tokens)

        val ifBody = parseStatement(tokens) ?: throw ScriptError(pos, "Bad if statement: expected statement")

        // could be else block:
        val t2 = tokens.next()

        // we generate different statements: optimization
        return if (t2.type == Token.Type.ID && t2.value == "else") {
            val elseBody = parseStatement(tokens) ?: throw ScriptError(pos, "Bad else statement: expected statement")
            return statement(start) {
                if (condition.execute(it).toBool())
                    ifBody.execute(it)
                else
                    elseBody.execute(it)
            }
        } else {
            tokens.previous()
            statement(start) {
                if (condition.execute(it).toBool())
                    ifBody.execute(it)
                else
                    ObjVoid
            }
        }
    }

    data class FnParamDef(
        val name: String,
        val pos: Pos,
        val defaultValue: Statement? = null
    )

    private fun parseFunctionDeclaration(tokens: CompilerContext): Statement {
        var t = tokens.next()
        val start = t.pos
        val name = if (t.type != Token.Type.ID)
            throw ScriptError(t.pos, "Expected identifier after 'fn'")
        else t.value

        t = tokens.next()
        if (t.type != Token.Type.LPAREN)
            throw ScriptError(t.pos, "Bad function definition: expected '(' after 'fn ${name}'")
        val params = mutableListOf<FnParamDef>()
        var defaultListStarted = false
        do {
            t = tokens.next()
            if (t.type == Token.Type.RPAREN)
                break
            if (t.type != Token.Type.ID)
                throw ScriptError(t.pos, "Expected identifier after '('")
            val n = tokens.next()
            val defaultValue = if (n.type == Token.Type.ASSIGN) {
                parseExpression(tokens)?.also { defaultListStarted = true }
                    ?: throw ScriptError(n.pos, "Expected initialization expression")
            } else {
                if (defaultListStarted)
                    throw ScriptError(n.pos, "requires default value too")
                if (n.type != Token.Type.COMMA)
                    tokens.previous()
                null
            }
            params.add(FnParamDef(t.value, t.pos, defaultValue))
        } while (true)

        // Here we should be at open body
        val fnStatements = parseBlock(tokens)

        var closure: Context? = null

        val fnBody = statement(t.pos) { callerContext ->
            callerContext.pos = start
            // restore closure where the function was defined:
            val context = closure ?: callerContext.raiseError("bug: closure not set")
            // load params from caller context
            for ((i, d) in params.withIndex()) {
                if (i < callerContext.args.size)
                    context.addItem(d.name, false, callerContext.args.list[i].value)
                else
                    context.addItem(
                        d.name,
                        false,
                        d.defaultValue?.execute(context)
                            ?: throw ScriptError(
                                context.args.callerPos,
                                "missing required argument #${1 + i}: ${d.name}"
                            )
                    )
            }
            // save closure
            fnStatements.execute(context)
        }
        return statement(start) { context ->
            // we added fn in the context. now we must save closure
            // for the function
            closure = context
            context.addItem(name, false, fnBody)
            // as the function can be called from anywhere, we have
            // saved the proper context in the closure
            fnBody
        }
    }

    private fun parseBlock(tokens: CompilerContext): Statement {
        val t = tokens.next()
        if (t.type != Token.Type.LBRACE)
            throw ScriptError(t.pos, "Expected block body start: {")
        val block = parseScript(t.pos, tokens)
        return statement(t.pos) {
            // block run on inner context:
            block.execute(it.copy(t.pos))
        }.also {
            val t1 = tokens.next()
            if (t1.type != Token.Type.RBRACE)
                throw ScriptError(t1.pos, "unbalanced braces: expected block body end: }")
        }
    }

    private fun parseVarDeclaration(kind: String, mutable: Boolean, tokens: CompilerContext): Statement {
        val nameToken = tokens.next()
        val start = nameToken.pos
        if (nameToken.type != Token.Type.ID)
            throw ScriptError(nameToken.pos, "Expected identifier after '$kind'")
        val name = nameToken.value
        val eqToken = tokens.next()
        var setNull = false
        if (eqToken.type != Token.Type.ASSIGN) {
            if (!mutable)
                throw ScriptError(start, "val must be initialized")
            else {
                tokens.previous()
                setNull = true
            }
        }
        val initialExpression = if (setNull) null else parseStatement(tokens)
            ?: throw ScriptError(eqToken.pos, "Expected initializer expression")
        return statement(nameToken.pos) { context ->
            if (context.containsLocal(name))
                throw ScriptError(nameToken.pos, "Variable $name is already defined")
            val initValue = initialExpression?.execute(context) ?: ObjNull
            context.addItem(name, mutable, initValue)
            ObjVoid
        }
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

    data class Operator(
        val tokenType: Token.Type,
        val priority: Int, val arity: Int,
        val generate: (Pos, Statement, Statement) -> Statement
    )

    companion object {

        val allOps = listOf(
            Operator(Token.Type.OR, 0, 2) { pos, a, b -> LogicalOrStatement(pos, a, b) },
            Operator(Token.Type.AND, 1, 2) { pos, a, b -> LogicalAndStatement(pos, a, b) },
            // bitwise or 2
            // bitwise and 3
            // equality/ne 4
            LogicalOp(Token.Type.EQ, 4) { a, b -> a == b },
            LogicalOp(Token.Type.NEQ, 4) { a, b -> a != b },
            // relational <=,... 5
            LogicalOp(Token.Type.LTE, 5) { a, b -> a <= b },
            LogicalOp(Token.Type.LT, 5) { a, b -> a < b },
            LogicalOp(Token.Type.GTE, 5) { a, b -> a >= b },
            LogicalOp(Token.Type.GT, 5) { a, b -> a > b },
            // shuttle <=> 6
            // bitshhifts 7
            Operator(Token.Type.PLUS, 8, 2) { pos, a, b ->
                PlusStatement(pos, a, b)
            },
            Operator(Token.Type.MINUS, 8, 2) { pos, a, b ->
                MinusStatement(pos, a, b)
            },
            Operator(Token.Type.STAR, 9, 2) { pos, a, b -> MulStatement(pos, a, b) },
            Operator(Token.Type.SLASH, 9, 2) { pos, a, b -> DivStatement(pos, a, b) },
            Operator(Token.Type.PERCENT, 9, 2) { pos, a, b -> ModStatement(pos, a, b) },
        )
        val lastLevel = 10
        val byLevel: List<Map<Token.Type, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }
                .map { it.tokenType to it }.toMap()
        }

        fun compile(code: String): Script = Compiler().compile(Source("<eval>", code))
    }
}

suspend fun eval(code: String) = Compiler.compile(code).execute()

fun LogicalOp(tokenType: Token.Type, priority: Int, f: (Obj, Obj) -> Boolean) = Compiler.Operator(
    tokenType,
    priority,
    2
) { pos, a, b ->
    statement(pos) {
        ObjBool(
            f(a.execute(it), b.execute(it))
        )
    }
}