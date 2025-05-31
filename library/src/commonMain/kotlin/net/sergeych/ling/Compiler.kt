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
//                    val next = tokens.next()
//                    if (next.type == Token.Type.ASSIGN) {
//                         this _is_ assignment statement
//                        return AssignStatement(
//                            t.pos, t.value,
//                            parseStatement(tokens) ?: throw ScriptError(
//                                t.pos,
//                                "Expecting expression for assignment operator"
//                            )
//                        )
//                    }
//                     not assignment, maybe keyword statement:
//                     get back the token which is not '=':
//                    tokens.previous()
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

    private fun parseExpression(tokens: CompilerContext): Statement? {
        val pos = tokens.currentPos()
        return parseExpressionLevel(tokens)?.let { a -> statement(pos) { a.getter(it).value } }
    }

    private fun parseExpressionLevel(tokens: CompilerContext, level: Int = 0): Accessor? {
        if (level == lastLevel)
            return parseTerm(tokens)
        var lvalue = parseExpressionLevel(tokens, level + 1)
        if (lvalue == null) return null

        while (true) {

            val opToken = tokens.next()
            val op = byLevel[level][opToken.type]
            if (op == null) {
                tokens.previous()
                break
            }

            val rvalue = parseExpressionLevel(tokens, level + 1)
                ?: throw ScriptError(opToken.pos, "Expecting expression")

            lvalue = op.generate(opToken.pos, lvalue!!, rvalue)
        }
        return lvalue
    }

    private fun parseTerm(cc: CompilerContext): Accessor? {
        var operand: Accessor? = null

        while (true) {
            val t = cc.next()
            val startPos = t.pos
            when (t.type) {
                Token.Type.NEWLINE, Token.Type.SEMICOLON, Token.Type.EOF, Token.Type.RBRACE, Token.Type.COMMA -> {
                    cc.previous()
                    return operand
                }

                Token.Type.NOT -> {
                    if (operand != null) throw ScriptError(t.pos, "unexpected operator not '!'")
                    val op = parseTerm(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                    operand = Accessor { op.getter(it).value.logicalNot(it).asReadonly }
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
                                    val v = left.getter(context).value
                                    WithAccess(
                                        v.callInstanceMethod(
                                            context,
                                            next.value,
                                            args.toArguments(context)
                                        ), isMutable = false
                                    )
                                }
                            }
                        }
                        if (!isCall) {
                            operand = Accessor { context ->
                                left.getter(context).value.readField(context, next.value)
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
                            statement.execute(it).asReadonly
                        }
                        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                        cc.skipTokenOfType(Token.Type.RPAREN, "missing ')'")
                    }
                }

                Token.Type.LBRACKET -> {
                    operand?.let { left ->
                        // array access
                        val index = parseStatement(cc) ?: throw ScriptError(t.pos, "Expecting index expression")
                        cc.skipTokenOfType(Token.Type.RBRACKET, "missing ']' at the end of the list literal")
                        operand = Accessor({ cxt ->
                            val i = (index.execute(cxt) as? ObjInt)?.value?.toInt()
                                ?: cxt.raiseError("index must be integer")
                            left.getter(cxt).value.getAt(cxt, i).asMutable
                        }) { cxt, newValue ->
                            val i = (index.execute(cxt) as? ObjInt)?.value?.toInt()
                                ?: cxt.raiseError("index must be integer")
                            left.getter(cxt).value.putAt(cxt, i, newValue)
                        }
                    } ?: run {
                        // array literal
                        val entries = parseArrayLiteral(cc)
                        // if it didn't throw, ot parsed ot and consumed it all
                        operand = Accessor { cxt ->
                            val list = mutableListOf<Obj>()
                            for (e in entries) {
                                when (e) {
                                    is ListEntry.Element -> {
                                        list += e.accessor.getter(cxt).value
                                    }

                                    is ListEntry.Spread -> {
                                        val elements = e.accessor.getter(cxt).value
                                        when {
                                            elements is ObjList -> list.addAll(elements.list)
                                            else -> cxt.raiseError("Spread element must be list")
                                        }
                                    }
                                }
                            }
                            ObjList(list).asReadonly
                        }
                    }
                }

                Token.Type.ID -> {
                    // there could be terminal operators or keywords:// variable to read or like
                    when (t.value) {
                        in stopKeywords -> {
                            if (operand != null) throw ScriptError(t.pos, "unexpected keyword")
                            cc.previous()
                            val s = parseStatement(cc) ?: throw ScriptError(t.pos, "Expecting valid statement")
                            operand = Accessor { s.execute(it).asReadonly }
                        }

                        "else", "break", "continue" -> {
                            cc.previous()
                            return operand

                        }

                        else -> operand?.let { left ->
                            // selector: <lvalue>, '.' , <id>
                            // we replace operand with selector code, that
                            // is RW:
                            operand = Accessor({
                                it.pos = t.pos
                                left.getter(it).value.readField(it, t.value)
                            }) { cxt, newValue ->
                                cxt.pos = t.pos
                                left.getter(cxt).value.writeField(cxt, t.value, newValue)
                            }
                        } ?: run {
                            // variable to read or like
                            cc.previous()
                            operand = parseAccessor(cc)
                        }
                    }
                }

                Token.Type.PLUS2 -> {
                    // note: post-increment result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post increment
                        left.setter(startPos)
                        operand = Accessor({ cxt ->
                            val x = left.getter(cxt)
                            if (x.isMutable)
                                x.value.getAndIncrement(cxt).asReadonly
                            else cxt.raiseError("Cannot increment immutable value")
                        })
                    } ?: run {
                        // no lvalue means pre-increment, expression to increment follows
                        val next = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor { ctx ->
                            next.getter(ctx).also {
                                if (!it.isMutable) ctx.raiseError("Cannot increment immutable value")
                            }.value.incrementAndGet(ctx).asReadonly
                        }
                    }
                }

                Token.Type.MINUS2 -> {
                    // note: post-decrement result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post decrement
                        left.setter(startPos)
                        operand = Accessor { ctx ->
                            left.getter(ctx).also {
                                if (!it.isMutable) ctx.raiseError("Cannot decrement immutable value")
                            }.value.getAndDecrement(ctx).asReadonly
                        }
                    } ?: run {
                        // no lvalue means pre-decrement, expression to decrement follows
                        val next = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor { ctx ->
                            next.getter(ctx).also {
                                if (!it.isMutable) ctx.raiseError("Cannot decrement immutable value")
                            }.value.decrementAndGet(ctx).asReadonly
                        }
                    }

                }


                else -> {
                    cc.previous()
                    operand?.let { return it }
                    operand = parseAccessor(cc) ?: throw ScriptError(t.pos, "Expecting expression")
                }
            }
        }
    }

    private fun parseArrayLiteral(cc: CompilerContext): List<ListEntry> {
        // it should be called after LBRACKET is consumed
        val entries = mutableListOf<ListEntry>()
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.COMMA -> {
                    // todo: check commas sequences like [,] [,,] before, after or instead of expressions
                }

                Token.Type.RBRACKET -> return entries
                Token.Type.ELLIPSIS -> {
                    parseExpressionLevel(cc)?.let { entries += ListEntry.Spread(it) }
                }

                else -> {
                    cc.previous()
                    parseExpressionLevel(cc)?.let { entries += ListEntry.Element(it) }
                        ?: throw ScriptError(t.pos, "invalid list literal: expecting expression")
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
                operand.getter(it).value.objClass.asReadonly
            }

            else -> throw ScriptError(t.pos, "Unknown scope operation: ${t.value}")
        }
    }

    private fun parseArgs(cc: CompilerContext): List<ParsedArgument> {
        val args = mutableListOf<ParsedArgument>()
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.RPAREN, Token.Type.COMMA -> {}
                Token.Type.ELLIPSIS -> {
                    parseStatement(cc)?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    parseStatement(cc)?.let { args += ParsedArgument(it, t.pos) }
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
            v.value.callOn(
                context.copy(
                    context.pos,
                    args.toArguments(context)
//                Arguments(
//                    args.map { Arguments.Info((it.value as Statement).execute(context), it.pos) }
//                ),
                )
            ).asReadonly
        }
    }

    private fun parseAccessor(cc: CompilerContext): Accessor? {
        // could be: literal
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                cc.previous()
                val n = parseNumber(true, cc)
                Accessor {
                    n.asReadonly
                }
            }

            Token.Type.STRING -> Accessor { ObjString(t.value).asReadonly }

            Token.Type.CHAR -> Accessor { ObjChar(t.value[0]).asReadonly }

            Token.Type.PLUS -> {
                val n = parseNumber(true, cc)
                Accessor { n.asReadonly }
            }

            Token.Type.MINUS -> {
                val n = parseNumber(false, cc)
                Accessor { n.asReadonly }
            }

            Token.Type.ID -> {
                when (t.value) {
                    "void" -> Accessor { ObjVoid.asReadonly }
                    "null" -> Accessor { ObjNull.asReadonly }
                    "true" -> Accessor { ObjBool(true).asReadonly }
                    "false" -> Accessor { ObjBool(false).asReadonly }
                    else -> {
                        Accessor({
                            it.pos = t.pos
                            it.get(t.value)?.asAccess
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
        "for" -> parseForStatement(cc)
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

    private fun parseForStatement(cc: CompilerContext): Statement {
        val label = getLabel(cc)?.also { cc.labels += it }
        val start = ensureLparen(cc)

        // for - in?
        val tVar = cc.next()
        if (tVar.type != Token.Type.ID)
            throw ScriptError(tVar.pos, "Bad for statement: expected loop variable")
        val tOp = cc.next()
        if (tOp.value == "in") {
            // in loop
            val source = parseStatement(cc) ?: throw ScriptError(start, "Bad for statement: expected expression")
            ensureRparen(cc)
            val body = parseStatement(cc) ?: throw ScriptError(start, "Bad for statement: expected loop body")

            // possible else clause
            cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
            val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
                parseStatement(cc)
            } else {
                cc.previous()
                null
            }


            return statement(body.pos) {
                val forContext = it.copy(start)

                // loop var: StoredObject
                val loopSO = forContext.addItem(tVar.value, true, ObjNull)

                // insofar we suggest source object is enumerable. Later we might need to add checks
                val sourceObj = source.execute(forContext)
                val size = runCatching { sourceObj.callInstanceMethod(forContext, "size").toInt() }
                    .getOrElse { throw ScriptError(tOp.pos, "object is not enumerable: no size") }
                var result: Obj = ObjVoid
                var breakCaught = false
                if (size > 0) {
                    var current = runCatching { sourceObj.getAt(forContext, 0) }
                        .getOrElse {
                            throw ScriptError(
                                tOp.pos,
                                "object is not enumerable: no index access for ${sourceObj.inspect()}",
                                it
                            )
                        }
                    var index = 0
                    while (true) {
                        loopSO.value = current
                        try {
                            result = body.execute(forContext)
                        } catch (lbe: LoopBreakContinueException) {
                            if (lbe.label == label || lbe.label == null) {
                                breakCaught = true
                                if (lbe.doContinue) continue
                                else {
                                    result = lbe.result
                                    break
                                }
                            } else
                                throw lbe
                        }
                        if (++index >= size) break
                        current = sourceObj.getAt(forContext, index)
                    }
                }
                if( !breakCaught && elseStatement != null) {
                    result = elseStatement.execute(it)
                }
                result
            }
        } else {
            // maybe other loops?
            throw ScriptError(tOp.pos, "Unsupported for-loop syntax")
        }
    }

    private fun parseWhileStatement(cc: CompilerContext): Statement {
        val label = getLabel(cc)?.also { cc.labels += it }
        val start = ensureLparen(cc)
        val condition = parseExpression(cc) ?: throw ScriptError(start, "Bad while statement: expected expression")
        ensureRparen(cc)

        val body = parseStatement(cc) ?: throw ScriptError(start, "Bad while statement: expected statement")
        label?.also { cc.labels -= it }

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
            parseStatement(cc)
        } else {
            cc.previous()
            null
        }
        return statement(body.pos) {
            var result: Obj = ObjVoid
            while (condition.execute(it).toBool()) {
                try {
                    // we don't need to create new context here: if body is a block,
                    // parse block will do it, otherwise single statement doesn't need it:
                    result = body.execute(it)
                    elseStatement?.let { s -> result = s.execute(it) }
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

            // init value could be a val; when we init by-value type var with it, we need to
            // create a separate copy:
            val initValue = initialExpression?.execute(context)?.byValueCopy() ?: ObjNull

            context.addItem(name, mutable, initValue)
            ObjVoid
        }
    }

    data class Operator(
        val tokenType: Token.Type,
        val priority: Int, val arity: Int = 2,
        val generate: (Pos, Accessor, Accessor) -> Accessor
    ) {
//        fun isLeftAssociative() = tokenType != Token.Type.OR && tokenType != Token.Type.AND

        companion object {
            fun simple(tokenType: Token.Type, priority: Int, f: suspend (Context, Obj, Obj) -> Obj): Operator =
                Operator(tokenType, priority, 2, { _: Pos, a: Accessor, b: Accessor ->
                    Accessor { f(it, a.getter(it).value, b.getter(it).value).asReadonly }
                })
        }

    }

    companion object {

        private var lastPrty = 0
        val allOps = listOf(
            // assignments
            Operator(Token.Type.ASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val value = b.getter(it).value
                    val access = a.getter(it)
                    if (!access.isMutable) throw ScriptError(pos, "cannot assign to immutable variable")
                    if (access.value.assign(it, value) == null)
                        a.setter(pos)(it, value)
                    value.asReadonly
                }
            },
            Operator(Token.Type.PLUSASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val x = a.getter(it).value
                    val y = b.getter(it).value
                    (x.plusAssign(it, y) ?: run {
                        val result = x.plus(it, y)
                        a.setter(pos)(it, result)
                        result
                    }).asReadonly
                }
            },
            Operator(Token.Type.MINUSASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val x = a.getter(it).value
                    val y = b.getter(it).value
                    (x.minusAssign(it, y) ?: run {
                        val result = x.minus(it, y)
                        a.setter(pos)(it, result)
                        result
                    }).asReadonly
                }
            },
            Operator(Token.Type.STARASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val x = a.getter(it).value
                    val y = b.getter(it).value
                    (x.mulAssign(it, y) ?: run {
                        val result = x.mul(it, y)
                        a.setter(pos)(it, result)
                        result

                    }).asReadonly
                }
            },
            Operator(Token.Type.SLASHASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val x = a.getter(it).value
                    val y = b.getter(it).value
                    (x.divAssign(it, y) ?: run {
                        val result = x.div(it, y)
                        a.setter(pos)(it, result)
                        result
                    }).asReadonly
                }
            },
            Operator(Token.Type.PERCENTASSIGN, lastPrty) { pos, a, b ->
                Accessor {
                    val x = a.getter(it).value
                    val y = b.getter(it).value
                    (x.modAssign(it, y) ?: run {
                        val result = x.mod(it, y)
                        a.setter(pos)(it, result)
                        result
                    }).asReadonly
                }
            },
            // logical 1
            Operator.simple(Token.Type.OR, ++lastPrty) { ctx, a, b -> a.logicalOr(ctx, b) },
            // logical 2
            Operator.simple(Token.Type.AND, ++lastPrty) { ctx, a, b -> a.logicalAnd(ctx, b) },
            // bitwise or 2
            // bitwise and 3
            // equality/ne 4
            Operator.simple(Token.Type.EQ, ++lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) == 0) },
            Operator.simple(Token.Type.NEQ, lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) != 0) },
            Operator.simple(Token.Type.REF_EQ, lastPrty) { _, a, b -> ObjBool(a === b) },
            Operator.simple(Token.Type.REF_NEQ, lastPrty) { _, a, b -> ObjBool(a !== b) },
            // relational <=,... 5
            Operator.simple(Token.Type.LTE, ++lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) <= 0) },
            Operator.simple(Token.Type.LT, lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) < 0) },
            Operator.simple(Token.Type.GTE, lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) >= 0) },
            Operator.simple(Token.Type.GT, lastPrty) { c, a, b -> ObjBool(a.compareTo(c, b) > 0) },
            // shuttle <=> 6
            // bit shifts 7
            Operator.simple(Token.Type.PLUS, ++lastPrty) { ctx, a, b -> a.plus(ctx, b) },
            Operator.simple(Token.Type.MINUS, lastPrty) { ctx, a, b -> a.minus(ctx, b) },

            Operator.simple(Token.Type.STAR, ++lastPrty) { ctx, a, b -> a.mul(ctx, b) },
            Operator.simple(Token.Type.SLASH, lastPrty) { ctx, a, b -> a.div(ctx, b) },
            Operator.simple(Token.Type.PERCENT, lastPrty) { ctx, a, b -> a.mod(ctx, b) },
        )

//        private val assigner = allOps.first { it.tokenType == Token.Type.ASSIGN }
//
//        fun performAssignment(context: Context, left: Accessor, right: Accessor) {
//            assigner.generate(context.pos, left, right)
//        }

        val lastLevel = lastPrty + 1

        val byLevel: List<Map<Token.Type, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }
                .map { it.tokenType to it }.toMap()
        }

        fun compile(code: String): Script = Compiler().compile(Source("<eval>", code))

        /**
         * The keywords that stop processing of expression term
         */
        val stopKeywords = setOf("break", "continue", "return", "if", "when", "do", "while", "for")
    }
}

suspend fun eval(code: String) = Compiler.compile(code).execute()

