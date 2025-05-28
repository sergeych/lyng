package net.sergeych.ling

/**
 * The LING compiler.
 */
class Compiler(
    @Suppress("UNUSED_PARAMETER")
    settings: Settings = Settings()
) {

    class Settings

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
            return parseTerm3(tokens)
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


    /*
    Term compiler

    Fn calls could be:

    1) Fn(...)
    2) thisObj.method(...)

    1 is a shortcut to this.Fn(...)

    In general, we can assume any Fn to be of the same king, with `this` that always exist, and set by invocation.

    In the case of (1), so called regular, or not bound function, it takes current this from the context.
    In the case of (2), bound function, it creates sub-context binding thisObj to `this` in it.

    Suppose we do regular parsing. THen we get lparam = statement, and parse to the `(`. Now we have to
    compile the invocation of lparam, which can be thisObj.method or just method(). Unfortunately we
    already compiled it and can't easily restore its type, so we have to parse it different way.

    EBNF to parse term having lparam.

        boundcall = "." , identifier, "("

     We then call instance method bound to `lparam`.

        call = "(', args, ")

    we treat current lparam as callable and invoke it on the current context with current value of 'this.

    Just traversing fields:

        traverse = ".", not (identifier , ".")

    Other cases to parse:

        index = lparam, "[" , ilist , "]"


     */

    /**
     * Lower level of expr:
     *
     * assigning expressions:
     *
     * expr = expr: assignment
     * ++expr, expr++, --expr, expr--,
     *
     * update-assigns:
     * expr += expr, ...
     *
     * Dot!:   expr , '.', ID
     * Lambda: { <expr> }
     * index: expr[ ilist ]
     * call: <expr>( ilist )
     * self updating: ++expr, expr++, --expr, expr--, expr+=<expr>,
     *                expr-=<expr>, expr*=<expr>, expr/=<expr>
     * read expr: <expr>
     */
    private fun parseTerm3(cc: CompilerContext): Statement? {
        var operand: Accessor? = null

        while (true) {
            val t = cc.next()
            val startPos = t.pos
            when (t.type) {
                Token.Type.NEWLINE, Token.Type.SEMICOLON, Token.Type.EOF -> {
                    cc.previous()
                    return operand?.let { op -> statement(startPos) { op.getter(it) } }
                }

                Token.Type.DOT -> {
                    operand?.let { left ->
                        // dotcall: calling method on the operand, if next is ID, "("
                        var isCall = false
                        val next = cc.next()
                        if (next.type == Token.Type.ID) {
                            cc.ifNextIs(Token.Type.LPAREN) {
                                // instance method call
                                val args = parseArgs(cc)
                                isCall = true
                                operand = Accessor { context ->
                                    context.pos = next.pos
                                    val v = left.getter(context)
                                    v.callInstanceMethod(
                                        context,
                                        next.value,
                                        args.toArguments()
                                    )
                                }
                            }
                        }
                        if (!isCall) {
                            operand = Accessor { context ->
                                left.getter(context).readField(context, next.value)
                            }
                        }
                    } ?: throw ScriptError(t.pos, "Expecting expression before dot")
                }

                Token.Type.COLONCOLON -> {
                    operand = parseScopeOperator(operand, cc)
                }

                Token.Type.LPAREN -> {
                    operand?.let { left ->
                        // this is function call from <left>
                        operand = parseFunctionCall(
                            cc,
                            left,
                        )
                    } ?: run {
                        // Expression in parentheses
                        val statement = parseStatement(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor {
                            statement.execute(it)
                        }
                        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                        cc.skipTokenOfType(Token.Type.RPAREN, "missing ')'")
                    }
                }

                Token.Type.ID -> {
                    // there could be terminal operators or keywords:// variable to read or like
                    when (t.value) {
                        "else" -> {
                            cc.previous()
                            return operand?.let { op -> statement(startPos) { op.getter(it) } }
                        }

                        "if", "when", "do", "while", "return" -> {
                            if (operand != null) throw ScriptError(t.pos, "unexpected keyword")
                            cc.previous()
                            val s = parseStatement(cc) ?: throw ScriptError(t.pos, "Expecting valid statement")
                            operand = Accessor { s.execute(it) }
                        }

                        "break", "continue" -> {
                            cc.previous()
                            return operand?.let { op -> statement(startPos) { op.getter(it) } }

                        }

                        else -> operand?.let { left ->
                            // selector: <lvalue>, '.' , <id>
                            // we replace operand with selector code, that
                            // is RW:
                            operand = Accessor({
                                it.pos = t.pos
                                left.getter(it).readField(it, t.value)
                            }) { cxt, newValue ->
                                cxt.pos = t.pos
                                left.getter(cxt).writeField(cxt, t.value, newValue)
                            }
                        } ?: run {
                            // variable to read or like
                            cc.previous()
                            operand = parseAccessor(cc)
                        }
                    }
                    // selector: <lvalue>, '.' , <id>
                    // we replace operand with selector code, that
                    // is RW:
                }

                Token.Type.PLUS2 -> {
                    // note: post-increment result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post increment
                        left.setter(startPos)
                        operand = Accessor({ ctx ->
                            left.getter(ctx).getAndIncrement(ctx)
                        })
                    } ?: run {
                        // no lvalue means pre-increment, expression to increment follows
                        val next = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor({ ctx -> next.getter(ctx).incrementAndGet(ctx) })
                    }
                }

                Token.Type.MINUS2 -> {
                    // note: post-decrement result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post decrement
                        left.setter(startPos)
                        operand = Accessor { ctx ->
                            left.getter(ctx).getAndDecrement(ctx)
                        }
                    } ?: run {
                        // no lvalue means pre-decrement, expression to decrement follows
                        val next = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor { ctx -> next.getter(ctx).decrementAndGet(ctx) }
                    }

                }


                else -> {
                    cc.previous()
                    operand?.let { op ->
                        return statement(startPos) { op.getter(it) }
                    }
                    operand = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                }
            }
        }
    }

    private fun parseScopeOperator(operand: Accessor?, cc: CompilerContext): Accessor {
        // implement global scope maybe?
        if (operand == null) throw ScriptError(cc.next().pos, "Expecting expression before ::")
        val t = cc.next()
        if (t.type != Token.Type.ID) throw ScriptError(t.pos, "Expecting ID after ::")
        return when (t.value) {
            "class" -> Accessor {
                operand.getter(it).objClass
            }

            else -> throw ScriptError(t.pos, "Unknown scope operation: ${t.value}")
        }
    }

    private fun parseArgs(cc: CompilerContext): List<Arguments.Info> {
        val args = mutableListOf<Arguments.Info>()
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.RPAREN, Token.Type.COMMA -> {}
                else -> {
                    cc.previous()
                    parseStatement(cc)?.let { args += Arguments.Info(it, t.pos) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }
            }
        } while (t.type != Token.Type.RPAREN)
        return args
    }


    private fun parseFunctionCall(cc: CompilerContext, left: Accessor): Accessor {
        // insofar, functions always return lvalue
        val args = parseArgs(cc)

        return Accessor { context ->
            val v = left.getter(context)
            v.callOn(context.copy(
                context.pos,
                Arguments(
                    args.map { Arguments.Info((it.value as Statement).execute(context), it.pos) }
                ),
            )
            )
        }
    }

    private fun parseAccessor(cc: CompilerContext): Accessor? {
        // could be: literal
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                cc.previous()
                val n = parseNumber(true, cc)
                Accessor({ n })
            }

            Token.Type.STRING -> Accessor({ ObjString(t.value) })

            Token.Type.PLUS -> {
                val n = parseNumber(true, cc)
                Accessor { n }
            }

            Token.Type.MINUS -> {
                val n = parseNumber(false, cc)
                Accessor { n }
            }

            Token.Type.ID -> {
                when (t.value) {
                    "void" -> Accessor { ObjVoid }
                    "null" -> Accessor { ObjNull }
                    "true" -> Accessor { ObjBool(true) }
                    "false" -> Accessor { ObjBool(false) }
                    else -> {
                        Accessor({
                            it.pos = t.pos
                            it.get(t.value)?.value
                                ?: it.raiseError("symbol not defined: '${t.value}'")
                        }) { ctx, newValue ->
                            ctx.get(t.value)?.let { stored ->
                                ctx.pos = t.pos
                                if (stored.isMutable)
                                    stored.value = newValue
                                else
                                    ctx.raiseError("Cannot assign to immutable value")
                            } ?: ctx.raiseError("symbol not defined: '${t.value}'")
                        }
                    }
                }
            }

            else -> null
        }
    }

    private fun parseNumber(isPlus: Boolean, tokens: CompilerContext): Obj {
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

    private fun getLabel(cc: CompilerContext, maxDepth: Int = 2): String? {
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

        tokens.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        // could be else block:
        val t2 = tokens.next()

        // we generate different statements: optimization
        return if (t2.type == Token.Type.ID && t2.value == "else") {
            val elseBody =
                parseStatement(tokens) ?: throw ScriptError(pos, "Bad else statement: expected statement")
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
                                context.pos,
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
            LogicalOp(Token.Type.EQ, 4) { c, a, b -> a.compareTo(c, b) == 0 },
            LogicalOp(Token.Type.NEQ, 4) { c, a, b -> a.compareTo(c, b) != 0 },
            // relational <=,... 5
            LogicalOp(Token.Type.LTE, 5) { c, a, b -> a.compareTo(c, b) <= 0 },
            LogicalOp(Token.Type.LT, 5) { c, a, b -> a.compareTo(c, b) < 0 },
            LogicalOp(Token.Type.GTE, 5) { c, a, b -> a.compareTo(c, b) >= 0 },
            LogicalOp(Token.Type.GT, 5) { c, a, b -> a.compareTo(c, b) > 0 },
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

fun LogicalOp(
    tokenType: Token.Type, priority: Int,
    f: suspend (Context, Obj, Obj) -> Boolean
) = Compiler.Operator(
    tokenType,
    priority,
    2
) { pos, a, b ->
    statement(pos) {
        ObjBool(
            f(it, a.execute(it), b.execute(it))
        )
    }
}