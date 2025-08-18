/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

import ObjEnumClass
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportProvider

/**
 * The LYNG compiler.
 */
class Compiler(
    val cc: CompilerContext,
    val importManager: ImportProvider,
    @Suppress("UNUSED_PARAMETER")
    settings: Settings = Settings()
) {

    var packageName: String? = null

    class Settings

    private val initStack = mutableListOf<MutableList<Statement>>()

    val currentInitScope: MutableList<Statement>
        get() =
            initStack.lastOrNull() ?: cc.syntaxError("no initialization scope exists here")

    private fun pushInitScope(): MutableList<Statement> = mutableListOf<Statement>().also { initStack.add(it) }

    private fun popInitScope(): MutableList<Statement> = initStack.removeLast()

    private val codeContexts = mutableListOf<CodeContext>(CodeContext.Module(null))

    private suspend fun <T> inCodeContext(context: CodeContext, f: suspend () -> T): T {
        return try {
            codeContexts.add(context)
            f()
        } finally {
            codeContexts.removeLast()
        }
    }

    private suspend fun parseScript(): Script {
        val statements = mutableListOf<Statement>()
        val start = cc.currentPos()
//        val returnScope = cc.startReturnScope()
        // package level declarations
        do {
            val t = cc.current()
            if (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINLGE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                cc.next()
                continue
            }
            if (t.type == Token.Type.ID) {
                when (t.value) {
                    "package" -> {
                        cc.next()
                        val name = loadQualifiedName()
                        if (name.isEmpty()) throw ScriptError(cc.currentPos(), "Expecting package name here")
                        if (packageName != null) throw ScriptError(
                            cc.currentPos(),
                            "package name redefined, already set to $packageName"
                        )
                        packageName = name
                        continue
                    }

                    "import" -> {
                        cc.next()
                        val pos = cc.currentPos()
                        val name = loadQualifiedName()
                        val module = importManager.prepareImport(pos, name, null)
                        statements += statement {
                            module.importInto(this, null)
                            ObjVoid
                        }
                        continue
                    }
                }
            }
            val s = parseStatement(braceMeansLambda = true)?.also {
                statements += it
            }
            if (s == null) {
                when (t.type) {
                    Token.Type.RBRACE, Token.Type.EOF, Token.Type.SEMICOLON -> {}
                    else ->
                        throw ScriptError(t.pos, "unexpeced `${t.value}` here")
                }
                break
            }

        } while (true)
        return Script(start, statements)//returnScope.needCatch)
    }

    fun loadQualifiedName(): String {
        val result = StringBuilder()
        var t = cc.next()
        while (t.type == Token.Type.ID) {
            result.append(t.value)
            t = cc.next()
            if (t.type == Token.Type.DOT) {
                result.append('.')
                t = cc.next()
            }
        }
        cc.previous()
        return result.toString()
    }

    private var lastAnnotation: (suspend (Scope, ObjString, Statement) -> Statement)? = null

    private suspend fun parseStatement(braceMeansLambda: Boolean = false): Statement? {
        lastAnnotation = null
        while (true) {
            val t = cc.next()
            return when (t.type) {
                Token.Type.ID -> {
                    parseKeywordStatement(t)
                        ?: run {
                            cc.previous()
                            parseExpression()
                        }
                }

                Token.Type.PLUS2, Token.Type.MINUS2 -> {
                    cc.previous()
                    parseExpression()
                }

                Token.Type.ATLABEL -> {
                    lastAnnotation = parseAnnotation(t)
                    continue
                }

                Token.Type.LABEL -> continue
                Token.Type.SINLGE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> continue

                Token.Type.NEWLINE -> continue

                Token.Type.SEMICOLON -> continue

                Token.Type.LBRACE -> {
                    cc.previous()
                    if (braceMeansLambda)
                        parseExpression()
                    else
                        parseBlock()
                }

                Token.Type.RBRACE, Token.Type.RBRACKET -> {
                    cc.previous()
                    return null
                }

                Token.Type.EOF -> null

                else -> {
                    // could be expression
                    cc.previous()
                    parseExpression()
                }
            }
        }
    }

    private suspend fun parseExpression(): Statement? {
        val pos = cc.currentPos()
        return parseExpressionLevel()?.let { a -> statement(pos) { a.getter(it).value } }
    }

    private suspend fun parseExpressionLevel(level: Int = 0): Accessor? {
        if (level == lastLevel)
            return parseTerm()
        var lvalue: Accessor? = parseExpressionLevel(level + 1) ?: return null

        while (true) {

            val opToken = cc.next()
            val op = byLevel[level][opToken.type]
            if (op == null) {
                cc.previous()
                break
            }

            val rvalue = parseExpressionLevel(level + 1)
                ?: throw ScriptError(opToken.pos, "Expecting expression")

            lvalue = op.generate(opToken.pos, lvalue!!, rvalue)
        }
        return lvalue
    }

    private suspend fun parseTerm(): Accessor? {
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
                    val op = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                    operand = Accessor { op.getter(it).value.logicalNot(it).asReadonly }
                }

                Token.Type.DOT, Token.Type.NULL_COALESCE -> {
                    val isOptional = t.type == Token.Type.NULL_COALESCE
                    operand?.let { left ->
                        // dot call: calling method on the operand, if next is ID, "("
                        var isCall = false
                        val next = cc.next()
                        if (next.type == Token.Type.ID) {
                            // could be () call or obj.method {} call
                            val nt = cc.current()
                            when (nt.type) {
                                Token.Type.LPAREN -> {
                                    cc.next()
                                    // instance method call
                                    val args = parseArgs().first
                                    isCall = true
                                    operand = Accessor { context ->
                                        context.pos = next.pos
                                        val v = left.getter(context).value
                                        if (v == ObjNull && isOptional)
                                            ObjNull.asReadonly
                                        else
                                            ObjRecord(
                                                v.invokeInstanceMethod(
                                                    context,
                                                    next.value,
                                                    args.toArguments(context, false)
                                                ), isMutable = false
                                            )
                                    }
                                }


                                Token.Type.LBRACE, Token.Type.NULL_COALESCE_BLOCKINVOKE -> {
                                    // single lambda arg, like assertTrows { ... }
                                    cc.next()
                                    isCall = true
                                    val lambda =
                                        parseLambdaExpression()
                                    operand = Accessor { context ->
                                        context.pos = next.pos
                                        val v = left.getter(context).value
                                        if (v == ObjNull && isOptional)
                                            ObjNull.asReadonly
                                        else
                                            ObjRecord(
                                                v.invokeInstanceMethod(
                                                    context,
                                                    next.value,
                                                    Arguments(listOf(lambda.getter(context).value), true)
                                                ), isMutable = false
                                            )
                                    }
                                }

                                else -> {}
                            }
                        }
                        if (!isCall) {
                            operand = Accessor({ context ->
                                val x = left.getter(context).value
                                if (x == ObjNull && isOptional) ObjNull.asReadonly
                                else x.readField(context, next.value)
                            }) { cxt, newValue ->
                                left.getter(cxt).value.writeField(cxt, next.value, newValue)
                            }
                        }
                    }

                        ?: throw ScriptError(t.pos, "Expecting expression before dot")
                }

                Token.Type.COLONCOLON -> {
                    operand = parseScopeOperator(operand)
                }

                Token.Type.LPAREN, Token.Type.NULL_COALESCE_INVOKE -> {
                    operand?.let { left ->
                        // this is function call from <left>
                        operand = parseFunctionCall(
                            left,
                            false,
                            t.type == Token.Type.NULL_COALESCE_INVOKE
                        )
                    } ?: run {
                        // Expression in parentheses
                        val statement = parseStatement() ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor {
                            statement.execute(it).asReadonly
                        }
                        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                        cc.skipTokenOfType(Token.Type.RPAREN, "missing ')'")
                    }
                }

                Token.Type.LBRACKET, Token.Type.NULL_COALESCE_INDEX -> {
                    operand?.let { left ->
                        // array access
                        val isOptional = t.type == Token.Type.NULL_COALESCE_INDEX
                        val index = parseStatement() ?: throw ScriptError(t.pos, "Expecting index expression")
                        cc.skipTokenOfType(Token.Type.RBRACKET, "missing ']' at the end of the list literal")
                        operand = Accessor({ cxt ->
                            val i = index.execute(cxt)
                            val x = left.getter(cxt).value
                            if (x == ObjNull && isOptional) ObjNull.asReadonly
                            else x.getAt(cxt, i).asMutable
                        }) { cxt, newValue ->
                            left.getter(cxt).value.putAt(cxt, index.execute(cxt), newValue)
                        }
                    } ?: run {
                        // array literal
                        val entries = parseArrayLiteral()
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
                            val s = parseStatement() ?: throw ScriptError(t.pos, "Expecting valid statement")
                            operand = Accessor { s.execute(it).asReadonly }
                        }

                        "else", "break", "continue" -> {
                            cc.previous()
                            return operand

                        }

                        "throw" -> {
                            val s = parseThrowStatement()
                            operand = Accessor {
                                s.execute(it).asReadonly
                            }
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
                            operand = parseAccessor()
                        }
                    }
                }

                Token.Type.PLUS2 -> {
                    // note: post-increment result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post increment
                        left.setter(startPos)
                        operand = Accessor { cxt ->
                            val x = left.getter(cxt)
                            if (x.isMutable) {
                                if (x.value.isConst) {
                                    x.value.plus(cxt, ObjInt.One).also {
                                        left.setter(startPos)(cxt, it)
                                    }.asReadonly
                                } else
                                    x.value.getAndIncrement(cxt).asReadonly
                            } else cxt.raiseError("Cannot increment immutable value")
                        }
                    } ?: run {
                        // no lvalue means pre-increment, expression to increment follows
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor { ctx ->
                            val x = next.getter(ctx).also {
                                if (!it.isMutable) ctx.raiseError("Cannot increment immutable value")
                            }.value
                            if (x.isConst) {
                                next.setter(startPos)(ctx, x.plus(ctx, ObjInt.One))
                                x.asReadonly
                            } else x.incrementAndGet(ctx).asReadonly
                        }
                    }
                }

                Token.Type.MINUS2 -> {
                    // note: post-decrement result is not assignable (truly lvalue)
                    operand?.let { left ->
                        // post decrement
                        left.setter(startPos)
                        operand = Accessor { cxt ->
                            val x = left.getter(cxt)
                            if (!x.isMutable) cxt.raiseError("Cannot decrement immutable value")
                            if (x.value.isConst) {
                                x.value.minus(cxt, ObjInt.One).also {
                                    left.setter(startPos)(cxt, it)
                                }.asReadonly
                            } else
                                x.value.getAndDecrement(cxt).asReadonly
                        }
                    } ?: run {
                        // no lvalue means pre-decrement, expression to decrement follows
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        operand = Accessor { cxt ->
                            val x = next.getter(cxt)
                            if (!x.isMutable) cxt.raiseError("Cannot decrement immutable value")
                            if (x.value.isConst) {
                                x.value.minus(cxt, ObjInt.One).also {
                                    next.setter(startPos)(cxt, it)
                                }.asReadonly
                            } else
                                x.value.decrementAndGet(cxt).asReadonly
                        }
                    }
                }

                Token.Type.DOTDOT, Token.Type.DOTDOTLT -> {
                    // range operator
                    val isEndInclusive = t.type == Token.Type.DOTDOT
                    val left = operand
                    val right = parseExpression()
                    operand = Accessor {
                        ObjRange(
                            left?.getter?.invoke(it)?.value ?: ObjNull,
                            right?.execute(it) ?: ObjNull,
                            isEndInclusive = isEndInclusive
                        ).asReadonly
                    }
                }

                Token.Type.LBRACE, Token.Type.NULL_COALESCE_BLOCKINVOKE -> {
                    operand = operand?.let { left ->
                        cc.previous()
                        parseFunctionCall(
                            left,
                            blockArgument = true,
                            t.type == Token.Type.NULL_COALESCE_BLOCKINVOKE
                        )
                    } ?: parseLambdaExpression()
                }

                Token.Type.RBRACKET, Token.Type.RPAREN -> {
                    cc.previous()
                    return operand
                }

                else -> {
                    cc.previous()
                    operand?.let { return it }
                    operand = parseAccessor() ?: return null //throw ScriptError(t.pos, "Expecting expression")
                }
            }
        }
    }

    /**
     * Parse lambda expression, leading '{' is already consumed
     */
    private suspend fun parseLambdaExpression(): Accessor {
        // lambda args are different:
        val startPos = cc.currentPos()
        val argsDeclaration = parseArgsDeclaration()
        if (argsDeclaration != null && argsDeclaration.endTokenType != Token.Type.ARROW)
            throw ScriptError(
                startPos,
                "lambda must have either valid arguments declaration with '->' or no arguments"
            )

        val body = parseBlock(skipLeadingBrace = true)

        var closure: Scope? = null

        val callStatement = statement {
            // and the source closure of the lambda which might have other thisObj.
            val context = this.applyClosure(closure!!)
            if (argsDeclaration == null) {
                // no args: automatic var 'it'
                val l = args.list
                val itValue: Obj = when (l.size) {
                    // no args: it == void
                    0 -> ObjVoid
                    // one args: it is this arg
                    1 -> l[0]
                    // more args: it is a list of args
                    else -> ObjList(l.toMutableList())
                }
                context.addItem("it", false, itValue, recordType = ObjRecord.Type.Argument)
            } else {
                // assign vars as declared the standard way
                argsDeclaration.assignToContext(context, defaultAccessType = AccessType.Val)
            }
            body.execute(context)
        }

        return Accessor { x ->
            closure = x
            callStatement.asReadonly
        }
    }

    private suspend fun parseArrayLiteral(): List<ListEntry> {
        // it should be called after Token.Type.LBRACKET is consumed
        val entries = mutableListOf<ListEntry>()
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.COMMA -> {
                    // todo: check commas sequences like [,] [,,] before, after or instead of expressions
                }

                Token.Type.RBRACKET -> return entries
                Token.Type.ELLIPSIS -> {
                    parseExpressionLevel()?.let { entries += ListEntry.Spread(it) }
                }

                else -> {
                    cc.previous()
                    parseExpressionLevel()?.let { entries += ListEntry.Element(it) }
                        ?: throw ScriptError(t.pos, "invalid list literal: expecting expression")
                }
            }
        }
    }

    private fun parseScopeOperator(operand: Accessor?): Accessor {
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

    /**
     * Parse argument declaration, used in lambda (and later in fn too)
     * @return declaration or null if there is no valid list of arguments
     */
    private suspend fun parseArgsDeclaration(isClassDeclaration: Boolean = false): ArgsDeclaration? {
        val result = mutableListOf<ArgsDeclaration.Item>()
        var endTokenType: Token.Type? = null
        val startPos = cc.savePos()

        while (endTokenType == null) {
            var t = cc.next()
            when (t.type) {
                Token.Type.RPAREN, Token.Type.ARROW -> {
                    // empty list?
                    endTokenType = t.type
                }

                Token.Type.NEWLINE -> {}

                Token.Type.ID -> {
                    // visibility
                    val visibility = if (isClassDeclaration && t.value == "private") {
                        t = cc.next()
                        Visibility.Private
                    } else Visibility.Public

                    // val/var?
                    val access = when (t.value) {
                        "val" -> {
                            if (!isClassDeclaration) {
                                cc.restorePos(startPos); return null
                            }
                            t = cc.next()
                            AccessType.Val
                        }

                        "var" -> {
                            if (!isClassDeclaration) {
                                cc.restorePos(startPos); return null
                            }
                            t = cc.next()
                            AccessType.Var
                        }

                        else -> null
                    }

                    var defaultValue: Statement? = null
                    cc.ifNextIs(Token.Type.ASSIGN) {
                        defaultValue = parseExpression()
                    }
                    // type information
                    val typeInfo = parseTypeDeclaration()
                    val isEllipsis = cc.skipTokenOfType(Token.Type.ELLIPSIS, isOptional = true)
                    result += ArgsDeclaration.Item(
                        t.value,
                        typeInfo,
                        t.pos,
                        isEllipsis,
                        defaultValue,
                        access,
                        visibility
                    )

                    // important: valid argument list continues with ',' and ends with '->' or ')'
                    // otherwise it is not an argument list:
                    when (val tt = cc.next().type) {
                        Token.Type.RPAREN -> {
                            // end of arguments
                            endTokenType = tt
                        }

                        Token.Type.ARROW -> {
                            // end of arguments too
                            endTokenType = tt
                        }

                        Token.Type.COMMA -> {
                            // next argument, OK
                        }

                        else -> {
                            // this is not a valid list of arguments:
                            cc.restorePos(startPos) // for the current
                            return null
                        }
                    }
                }

                else -> {
                    // if we get here. there os also no valid list of arguments:
                    cc.restorePos(startPos)
                    return null
                }
            }
        }
        return ArgsDeclaration(result, endTokenType)
    }

    private fun parseTypeDeclaration(): TypeDecl {
        return if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
            val tt = cc.requireToken(Token.Type.ID, "type name or type expression required")
            val isNullable = cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)
            TypeDecl.Simple(tt.value, isNullable)
        } else TypeDecl.TypeAny
    }

    /**
     * Parse arguments list during the call and detect last block argument
     * _following the parenthesis_ call: `(1,2) { ... }`
     */
    private suspend fun parseArgs(): Pair<List<ParsedArgument>, Boolean> {

        val args = mutableListOf<ParsedArgument>()
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.NEWLINE,
                Token.Type.RPAREN, Token.Type.COMMA -> {
                }

                Token.Type.ELLIPSIS -> {
                    parseStatement()?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    parseExpression()?.let { args += ParsedArgument(it, t.pos) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                    if (cc.current().type == Token.Type.COLON)
                        parseTypeDeclaration()
                    // Here should be a valid termination:
                }
            }
        } while (t.type != Token.Type.RPAREN)
        // block after?
        val pos = cc.savePos()
        val end = cc.next()
        var lastBlockArgument = false
        if (end.type == Token.Type.LBRACE) {
            // last argument - callable
            val callableAccessor = parseLambdaExpression()
            args += ParsedArgument(
                // transform accessor to the callable:
                statement {
                    callableAccessor.getter(this).value
                },
                end.pos
            )
            lastBlockArgument = true
        } else
            cc.restorePos(pos)
        return args to lastBlockArgument
    }


    private suspend fun parseFunctionCall(
        left: Accessor,
        blockArgument: Boolean,
        isOptional: Boolean
    ): Accessor {
        // insofar, functions always return lvalue
        var detectedBlockArgument = blockArgument
        val args = if (blockArgument) {
            val blockArg = ParsedArgument(
                parseExpression()
                    ?: throw ScriptError(cc.currentPos(), "lambda body expected"), cc.currentPos()
            )
            listOf(blockArg)
        } else {
            val r = parseArgs()
            detectedBlockArgument = r.second
            r.first
        }

        return Accessor { context ->
            val v = left.getter(context)
            if (v.value == ObjNull && isOptional) return@Accessor v.value.asReadonly
            v.value.callOn(
                context.copy(
                    context.pos,
                    args.toArguments(context, detectedBlockArgument)
//                Arguments(
//                    args.map { Arguments.Info((it.value as Statement).execute(context), it.pos) }
//                ),
                )
            ).asReadonly
        }
    }

    private suspend fun parseAccessor(): Accessor? {
        // could be: literal
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                cc.previous()
                val n = parseNumber(true)
                Accessor {
                    n.asReadonly
                }
            }

            Token.Type.STRING -> Accessor { ObjString(t.value).asReadonly }

            Token.Type.CHAR -> Accessor { ObjChar(t.value[0]).asReadonly }

            Token.Type.PLUS -> {
                val n = parseNumber(true)
                Accessor { n.asReadonly }
            }

            Token.Type.MINUS -> {
                parseNumberOrNull(false)?.let { n ->
                    Accessor { n.asReadonly }
                } ?: run {
                    val n = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression after unary minus")
                    Accessor {
                        n.getter.invoke(it).value.negate(it).asReadonly
                    }
                }
            }

            Token.Type.ID -> {
                when (t.value) {
                    "void" -> Accessor { ObjVoid.asReadonly }
                    "null" -> Accessor { ObjNull.asReadonly }
                    "true" -> Accessor { ObjBool(true).asReadonly }
                    "false" -> Accessor { ObjFalse.asReadonly }
                    else -> {
                        Accessor({
                            it.pos = t.pos
                            it[t.value]
                                ?: it.raiseError("symbol not defined: '${t.value}'")
                        }) { ctx, newValue ->
                            ctx[t.value]?.let { stored ->
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

    private fun parseNumberOrNull(isPlus: Boolean): Obj? {
        val pos = cc.savePos()
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.HEX -> {
                val n = t.value.replace("_", "").toLong(if (t.type == Token.Type.HEX) 16 else 10)
                if (isPlus) ObjInt(n) else ObjInt(-n)
            }

            Token.Type.REAL -> {
                val d = t.value.toDouble()
                if (isPlus) ObjReal(d) else ObjReal(-d)
            }

            else -> {
                cc.restorePos(pos)
                null
            }
        }
    }

    private fun parseNumber(isPlus: Boolean): Obj {
        return parseNumberOrNull(isPlus) ?: throw ScriptError(cc.currentPos(), "Expecting number")
    }

    suspend fun parseAnnotation(t: Token): (suspend (Scope, ObjString, Statement) -> Statement) {
        val extraArgs = parseArgsOrNull()
//        println("annotation ${t.value}: args: $extraArgs")
        return { scope, name, body ->
            val extras = extraArgs?.first?.toArguments(scope, extraArgs.second)?.list
            val required = listOf(name, body)
            val args = extras?.let { required + it } ?: required
            val fn = scope.get(t.value)?.value ?: scope.raiseSymbolNotFound("annotation not found: ${t.value}")
            if (fn !is Statement) scope.raiseIllegalArgument("annotation must be callable, got ${fn.objClass}")
            (fn.execute(scope.copy(Arguments(args))) as? Statement)
                ?: scope.raiseClassCastError("function annotation must return callable")
        }
    }

    suspend fun parseArgsOrNull(): Pair<List<ParsedArgument>, Boolean>? =
        if (cc.skipNextIf(Token.Type.LPAREN))
            parseArgs()
        else
            null

    /**
     * Parse keyword-starting statement.
     * @return parsed statement or null if, for example. [id] is not among keywords
     */
    private suspend fun parseKeywordStatement(id: Token): Statement? = when (id.value) {
        "val" -> parseVarDeclaration(false, Visibility.Public)
        "var" -> parseVarDeclaration(true, Visibility.Public)
        "while" -> parseWhileStatement()
        "do" -> parseDoWhileStatement()
        "for" -> parseForStatement()
        "break" -> parseBreakStatement(id.pos)
        "continue" -> parseContinueStatement(id.pos)
        "if" -> parseIfStatement()
        "class" -> parseClassDeclaration()
        "enum" -> parseEnumDeclaration()
        "try" -> parseTryStatement()
        "throw" -> parseThrowStatement()
        "when" -> parseWhenStatement()
        else -> {
            // triples
            cc.previous()
            val isExtern = cc.skipId("extern")
            when {
                cc.matchQualifiers("fun", "private") -> parseFunctionDeclaration(Visibility.Private, isExtern)
                cc.matchQualifiers("fun", "private", "static") -> parseFunctionDeclaration(
                    Visibility.Private,
                    isExtern,
                    isStatic = true
                )

                cc.matchQualifiers("fun", "static") -> parseFunctionDeclaration(
                    Visibility.Public,
                    isExtern,
                    isStatic = true
                )

                cc.matchQualifiers("fn", "private") -> parseFunctionDeclaration(Visibility.Private, isExtern)
                cc.matchQualifiers("fun", "open") -> parseFunctionDeclaration(isOpen = true, isExtern = isExtern)
                cc.matchQualifiers("fn", "open") -> parseFunctionDeclaration(isOpen = true, isExtern = isExtern)

                cc.matchQualifiers("fun") -> parseFunctionDeclaration(isOpen = false, isExtern = isExtern)
                cc.matchQualifiers("fn") -> parseFunctionDeclaration(isOpen = false, isExtern = isExtern)

                cc.matchQualifiers("val", "private", "static") -> parseVarDeclaration(
                    false,
                    Visibility.Private,
                    isStatic = true
                )

                cc.matchQualifiers("val", "static") -> parseVarDeclaration(false, Visibility.Public, isStatic = true)
                cc.matchQualifiers("val", "private") -> parseVarDeclaration(false, Visibility.Private)
                cc.matchQualifiers("var", "static") -> parseVarDeclaration(true, Visibility.Public, isStatic = true)
                cc.matchQualifiers("var", "static", "private") -> parseVarDeclaration(
                    true,
                    Visibility.Private,
                    isStatic = true
                )

                cc.matchQualifiers("var", "private") -> parseVarDeclaration(true, Visibility.Private)
                cc.matchQualifiers("val", "open") -> parseVarDeclaration(false, Visibility.Private, true)
                cc.matchQualifiers("var", "open") -> parseVarDeclaration(true, Visibility.Private, true)
                else -> {
                    cc.next()
                    null
                }
            }
        }
    }

    data class WhenCase(val condition: Statement, val block: Statement)

    private suspend fun parseWhenStatement(): Statement {
        // has a value, when(value) ?
        var t = cc.skipWsTokens()
        return if (t.type == Token.Type.LPAREN) {
            // when(value)
            val value = parseStatement() ?: throw ScriptError(cc.currentPos(), "when(value) expected")
            cc.skipTokenOfType(Token.Type.RPAREN)
            t = cc.next()
            if (t.type != Token.Type.LBRACE) throw ScriptError(t.pos, "when { ... } expected")
            val cases = mutableListOf<WhenCase>()
            var elseCase: Statement? = null
            lateinit var whenValue: Obj

            // there could be 0+ then clauses
            // condition could be a value, in and is clauses:
            // parse several conditions for one then clause

            // loop cases
            outer@ while (true) {

                var skipParseBody = false
                val currentCondition = mutableListOf<Statement>()

                // loop conditions
                while (true) {
                    t = cc.skipWsTokens()

                    when (t.type) {
                        Token.Type.IN,
                        Token.Type.NOTIN -> {
                            // we need a copy in the closure:
                            val isIn = t.type == Token.Type.IN
                            val container = parseExpression() ?: throw ScriptError(cc.currentPos(), "type expected")
                            currentCondition += statement {
                                val r = container.execute(this).contains(this, whenValue)
                                ObjBool(if (isIn) r else !r)
                            }
                        }

                        Token.Type.IS, Token.Type.NOTIS -> {
                            // we need a copy in the closure:
                            val isIn = t.type == Token.Type.IS
                            val caseType = parseExpression() ?: throw ScriptError(cc.currentPos(), "type expected")
                            currentCondition += statement {
                                val r = whenValue.isInstanceOf(caseType.execute(this))
                                ObjBool(if (isIn) r else !r)
                            }
                        }

                        Token.Type.COMMA ->
                            continue

                        Token.Type.ARROW ->
                            break

                        Token.Type.RBRACE ->
                            break@outer

                        else -> {
                            if (t.value == "else") {
                                cc.skipTokens(Token.Type.ARROW)
                                if (elseCase != null) throw ScriptError(
                                    cc.currentPos(),
                                    "when else block already defined"
                                )
                                elseCase =
                                    parseStatement() ?: throw ScriptError(
                                        cc.currentPos(),
                                        "when else block expected"
                                    )
                                skipParseBody = true
                            } else {
                                cc.previous()
                                val x = parseExpression()
                                    ?: throw ScriptError(cc.currentPos(), "when case condition expected")
                                currentCondition += statement {
                                    ObjBool(x.execute(this).compareTo(this, whenValue) == 0)
                                }
                            }
                        }
                    }
                }
                // parsed conditions?
                if (!skipParseBody) {
                    val block = parseStatement() ?: throw ScriptError(cc.currentPos(), "when case block expected")
                    for (c in currentCondition) cases += WhenCase(c, block)
                }
            }
            statement {
                var result: Obj = ObjVoid
                // in / is and like uses whenValue from closure:
                whenValue = value.execute(this)
                var found = false
                for (c in cases)
                    if (c.condition.execute(this).toBool()) {
                        result = c.block.execute(this)
                        found = true
                        break
                    }
                if (!found && elseCase != null) result = elseCase.execute(this)
                result
            }
        } else {
            // when { cond -> ... }
            TODO("when without object is not yet implemented")
        }
    }

    private suspend fun parseThrowStatement(): Statement {
        val throwStatement = parseStatement() ?: throw ScriptError(cc.currentPos(), "throw object expected")
        return statement {
            var errorObject = throwStatement.execute(this)
            if (errorObject is ObjString)
                errorObject = ObjException(this, errorObject.value)
            if (errorObject is ObjException)
                raiseError(errorObject)
            else raiseError("this is not an exception object: $errorObject")
        }
    }

    private data class CatchBlockData(
        val catchVar: Token,
        val classNames: List<String>,
        val block: Statement
    )

    private suspend fun parseTryStatement(): Statement {
        val body = parseBlock()
        val catches = mutableListOf<CatchBlockData>()
        cc.skipTokens(Token.Type.NEWLINE)
        var t = cc.next()
        while (t.value == "catch") {

            if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                t = cc.next()
                if (t.type != Token.Type.ID) throw ScriptError(t.pos, "expected catch variable")
                val catchVar = t

                val exClassNames = mutableListOf<String>()
                if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
                    // load list of exception classes
                    do {
                        t = cc.next()
                        if (t.type != Token.Type.ID)
                            throw ScriptError(t.pos, "expected exception class name")
                        exClassNames += t.value
                        t = cc.next()
                        when (t.type) {
                            Token.Type.COMMA -> {
                                continue
                            }

                            Token.Type.RPAREN -> {
                                break
                            }

                            else -> throw ScriptError(t.pos, "syntax error: expected ',' or ')'")
                        }
                    } while (true)
                } else {
                    // no type!
                    exClassNames += "Exception"
                    cc.skipTokenOfType(Token.Type.RPAREN)
                }
                val block = parseBlock()
                catches += CatchBlockData(catchVar, exClassNames, block)
                cc.skipTokens(Token.Type.NEWLINE)
                t = cc.next()
            } else {
                // no (e: Exception) block: should be the shortest variant `catch { ... }`
                cc.skipTokenOfType(Token.Type.LBRACE, "expected catch(...) or catch { ... } here")
                catches += CatchBlockData(
                    Token("it", cc.currentPos(), Token.Type.ID), listOf("Exception"),
                    parseBlock(true)
                )
                t = cc.next()
            }
        }
        val finallyClause = if (t.value == "finally") {
            parseBlock()
        } else {
            cc.previous()
            null
        }

        if (catches.isEmpty() && finallyClause == null)
            throw ScriptError(cc.currentPos(), "try block must have either catch or finally clause or both")

        return statement {
            var result: Obj = ObjVoid
            try {
                // body is a parsed block, it already has separate context
                result = body.execute(this)
            } catch (e: Exception) {
                // convert to appropriate exception
                val objException = when (e) {
                    is ExecutionError -> e.errorObject
                    else -> ObjUnknownException(this, e.message ?: e.toString())
                }
                // let's see if we should catch it:
                var isCaught = false
                for (cdata in catches) {
                    var exceptionObject: ObjException? = null
                    for (exceptionClassName in cdata.classNames) {
                        val exObj = ObjException.getErrorClass(exceptionClassName)
                            ?: raiseSymbolNotFound("error clas not exists: $exceptionClassName")
                        if (objException.isInstanceOf(exObj)) {
                            exceptionObject = objException
                            break
                        }
                    }
                    if (exceptionObject != null) {
                        val catchContext = this.copy(pos = cdata.catchVar.pos)
                        catchContext.addItem(cdata.catchVar.value, false, objException)
                        result = cdata.block.execute(catchContext)
                        isCaught = true
                        break
                    }
                }
                // rethrow if not caught this exception
                if (!isCaught)
                    throw e
            } finally {
                // finally clause does not alter result!
                finallyClause?.execute(this)
            }
            result
        }
    }

    private fun parseEnumDeclaration(): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        // so far only simplest enums:
        val names = mutableListOf<String>()
        // skip '{'
        cc.skipTokenOfType(Token.Type.LBRACE)

        do {
            val t = cc.skipWsTokens()
            when (t.type) {
                Token.Type.ID -> {
                    names += t.value
                    val t1 = cc.skipWsTokens()
                    when (t1.type) {
                        Token.Type.COMMA ->
                            continue

                        Token.Type.RBRACE -> break
                        else -> {
                            t1.raiseSyntax("unexpected token")
                        }
                    }
                }

                else -> t.raiseSyntax("expected enum entry name")
            }
        } while (true)

        return statement {
            ObjEnumClass.createSimpleEnum(nameToken.value, names).also {
                addItem(nameToken.value, false, it, recordType = ObjRecord.Type.Enum)
            }
        }
    }

    private suspend fun parseClassDeclaration(): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        return inCodeContext(CodeContext.ClassBody(nameToken.value)) {
            val constructorArgsDeclaration =
                if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true))
                    parseArgsDeclaration(isClassDeclaration = true)
                else null

            if (constructorArgsDeclaration != null && constructorArgsDeclaration.endTokenType != Token.Type.RPAREN)
                throw ScriptError(
                    nameToken.pos,
                    "Bad class declaration: expected ')' at the end of the primary constructor"
                )

            cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
            val t = cc.next()

            pushInitScope()

            val bodyInit: Statement? = if (t.type == Token.Type.LBRACE) {
                // parse body
                parseScript().also {
                    cc.skipTokens(Token.Type.RBRACE)
                }
            } else {
                cc.previous()
                null
            }

            val initScope = popInitScope()

            // create class
            val className = nameToken.value

//        @Suppress("UNUSED_VARIABLE") val defaultAccess = if (isStruct) AccessType.Var else AccessType.Initialization
//        @Suppress("UNUSED_VARIABLE") val defaultVisibility = Visibility.Public

            // create instance constructor
            // create custom objClass with all fields and instance constructor

            val constructorCode = statement {
                // constructor code is registered with class instance and is called over
                // new `thisObj` already set by class to ObjInstance.instanceContext
                thisObj as ObjInstance

                // the context now is a "class creation context", we must use its args to initialize
                // fields. Note that 'this' is already set by class
                constructorArgsDeclaration?.assignToContext(this)
                bodyInit?.execute(this)

                thisObj
            }
            // inheritance must alter this code:
            val newClass = ObjInstanceClass(className).apply {
                instanceConstructor = constructorCode
                constructorMeta = constructorArgsDeclaration
            }

            statement {
                // the main statement should create custom ObjClass instance with field
                // accessors, constructor registration, etc.
                addItem(className, false, newClass)
                if (initScope.isNotEmpty()) {
                    val classScope = copy(newThisObj = newClass)
                    newClass.classScope = classScope
                    for (s in initScope)
                        s.execute(classScope)
                }
                newClass
            }
        }

    }


    private fun getLabel(maxDepth: Int = 2): String? {
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

    private suspend fun parseForStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val start = ensureLparen()

        val tVar = cc.next()
        if (tVar.type != Token.Type.ID)
            throw ScriptError(tVar.pos, "Bad for statement: expected loop variable")
        val tOp = cc.next()
        if (tOp.value == "in") {
            // in loop
            val source = parseStatement() ?: throw ScriptError(start, "Bad for statement: expected expression")
            ensureRparen()

            val (canBreak, body) = cc.parseLoop {
                parseStatement() ?: throw ScriptError(start, "Bad for statement: expected loop body")
            }
            // possible else clause
            cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
            val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
                parseStatement()
            } else {
                cc.previous()
                null
            }


            return statement(body.pos) { ctx ->
                val forContext = ctx.copy(start)

                // loop var: StoredObject
                val loopSO = forContext.addItem(tVar.value, true, ObjNull)

                // insofar we suggest source object is enumerable. Later we might need to add checks
                val sourceObj = source.execute(forContext)

                if (sourceObj is ObjRange && sourceObj.isIntRange) {
                    loopIntRange(
                        forContext,
                        sourceObj.start!!.toInt(),
                        if (sourceObj.isEndInclusive) sourceObj.end!!.toInt() + 1 else sourceObj.end!!.toInt(),
                        loopSO, body, elseStatement, label, canBreak
                    )
                } else if (sourceObj.isInstanceOf(ObjIterable)) {
                    loopIterable(forContext, sourceObj, loopSO, body, elseStatement, label, canBreak)
                } else {
                    val size = runCatching { sourceObj.invokeInstanceMethod(forContext, "size").toInt() }
                        .getOrElse { throw ScriptError(tOp.pos, "object is not enumerable: no size", it) }

                    var result: Obj = ObjVoid
                    var breakCaught = false

                    if (size > 0) {
                        var current = runCatching { sourceObj.getAt(forContext, ObjInt(0)) }
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
                            if (canBreak) {
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
                            } else result = body.execute(forContext)
                            if (++index >= size) break
                            current = sourceObj.getAt(forContext, ObjInt(index.toLong()))
                        }
                    }
                    if (!breakCaught && elseStatement != null) {
                        result = elseStatement.execute(ctx)
                    }
                    result
                }
            }
        } else {
            // maybe other loops?
            throw ScriptError(tOp.pos, "Unsupported for-loop syntax")
        }
    }

    private suspend fun loopIntRange(
        forScope: Scope, start: Int, end: Int, loopVar: ObjRecord,
        body: Statement, elseStatement: Statement?, label: String?, catchBreak: Boolean
    ): Obj {
        var result: Obj = ObjVoid
        val iVar = ObjInt(0)
        loopVar.value = iVar
        if (catchBreak) {
            for (i in start..<end) {
                iVar.value = i.toLong()
                try {
                    result = body.execute(forScope)
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) continue
                        return lbe.result
                    }
                    throw lbe
                }
            }
        } else {
            for (i in start.toLong()..<end.toLong()) {
                iVar.value = i
                result = body.execute(forScope)
            }
        }
        return elseStatement?.execute(forScope) ?: result
    }

    private suspend fun loopIterable(
        forScope: Scope, sourceObj: Obj, loopVar: ObjRecord,
        body: Statement, elseStatement: Statement?, label: String?,
        catchBreak: Boolean
    ): Obj {
        val iterObj = sourceObj.invokeInstanceMethod(forScope, "iterator")
        var result: Obj = ObjVoid
        while (iterObj.invokeInstanceMethod(forScope, "hasNext").toBool()) {
            if (catchBreak)
                try {
                    loopVar.value = iterObj.invokeInstanceMethod(forScope, "next")
                    result = body.execute(forScope)
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) continue
                        return lbe.result
                    }
                    throw lbe
                }
            else {
                loopVar.value = iterObj.invokeInstanceMethod(forScope, "next")
                result = body.execute(forScope)
            }
        }
        return elseStatement?.execute(forScope) ?: result
    }

    @Suppress("UNUSED_VARIABLE")
    private suspend fun parseDoWhileStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val (breakFound, body) = cc.parseLoop {
            parseStatement() ?: throw ScriptError(cc.currentPos(), "Bad while statement: expected statement")
        }
        label?.also { cc.labels -= it }

        cc.skipTokens(Token.Type.NEWLINE)

        val t = cc.next()
        if (t.type != Token.Type.ID && t.value != "while")
            cc.skipTokenOfType(Token.Type.LPAREN, "expected '(' here")

        val conditionStart = ensureLparen()
        val condition =
            parseExpression() ?: throw ScriptError(conditionStart, "Bad while statement: expected expression")
        ensureRparen()

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
            parseStatement()
        } else {
            cc.previous()
            null
        }


        return statement(body.pos) {
            var wasBroken = false
            var result: Obj = ObjVoid
            lateinit var doScope: Scope
            do {
                doScope = it.copy().apply { skipScopeCreation = true }
                try {
                    result = body.execute(doScope)
                } catch (e: LoopBreakContinueException) {
                    if (e.label == label || e.label == null) {
                        if (e.doContinue) continue
                        else {
                            result = e.result
                            wasBroken = true
                            break
                        }
                    }
                    throw e
                }
            } while (condition.execute(doScope).toBool())
            if (!wasBroken) elseStatement?.let { s -> result = s.execute(it) }
            result
        }
    }

    private suspend fun parseWhileStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val start = ensureLparen()
        val condition =
            parseExpression() ?: throw ScriptError(start, "Bad while statement: expected expression")
        ensureRparen()

        val body = parseStatement() ?: throw ScriptError(start, "Bad while statement: expected statement")
        label?.also { cc.labels -= it }

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        val elseStatement = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
            parseStatement()
        } else {
            cc.previous()
            null
        }
        return statement(body.pos) {
            var result: Obj = ObjVoid
            var wasBroken = false
            while (condition.execute(it).toBool()) {
                try {
                    // we don't need to create new context here: if body is a block,
                    // parse block will do it, otherwise single statement doesn't need it:
                    result = body.execute(it)
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) continue
                        else {
                            result = lbe.result
                            wasBroken = true
                            break
                        }
                    } else
                        throw lbe
                }
            }
            if (!wasBroken) elseStatement?.let { s -> result = s.execute(it) }
            result
        }
    }

    private suspend fun parseBreakStatement(start: Pos): Statement {
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
            parseStatement()
        } else null

        cc.addBreak()

        return statement(start) {
            val returnValue = resultExpr?.execute(it)// ?: ObjVoid
            throw LoopBreakContinueException(
                doContinue = false,
                label = label,
                result = returnValue ?: ObjVoid
            )
        }
    }

    private fun parseContinueStatement(start: Pos): Statement {
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
        cc.addBreak()

        return statement(start) {
            throw LoopBreakContinueException(
                doContinue = true,
                label = label,
            )
        }
    }

    private fun ensureRparen(): Pos {
        val t = cc.next()
        if (t.type != Token.Type.RPAREN)
            throw ScriptError(t.pos, "expected ')'")
        return t.pos
    }

    private fun ensureLparen(): Pos {
        val t = cc.next()
        if (t.type != Token.Type.LPAREN)
            throw ScriptError(t.pos, "expected '('")
        return t.pos
    }

    private suspend fun parseIfStatement(): Statement {
        val start = ensureLparen()

        val condition = parseExpression()
            ?: throw ScriptError(start, "Bad if statement: expected expression")

        val pos = ensureRparen()

        val ifBody = parseStatement() ?: throw ScriptError(pos, "Bad if statement: expected statement")

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
        // could be else block:
        val t2 = cc.next()

        // we generate different statements: optimization
        return if (t2.type == Token.Type.ID && t2.value == "else") {
            val elseBody =
                parseStatement() ?: throw ScriptError(pos, "Bad else statement: expected statement")
            return statement(start) {
                if (condition.execute(it).toBool())
                    ifBody.execute(it)
                else
                    elseBody.execute(it)
            }
        } else {
            cc.previous()
            statement(start) {
                if (condition.execute(it).toBool())
                    ifBody.execute(it)
                else
                    ObjVoid
            }
        }
    }

    private suspend fun parseFunctionDeclaration(
        visibility: Visibility = Visibility.Public,
        @Suppress("UNUSED_PARAMETER") isOpen: Boolean = false,
        isExtern: Boolean = false,
        isStatic: Boolean = false,
    ): Statement {
        var t = cc.next()
        val start = t.pos
        var extTypeName: String? = null
        var name = if (t.type != Token.Type.ID)
            throw ScriptError(t.pos, "Expected identifier after 'fun'")
        else t.value

        val annotation = lastAnnotation
        val parentContext = codeContexts.last()

        t = cc.next()
        // Is extension?
        if (t.type == Token.Type.DOT) {
            extTypeName = name
            t = cc.next()
            if (t.type != Token.Type.ID)
                throw ScriptError(t.pos, "illegal extension format: expected function name")
            name = t.value
            t = cc.next()
        }

        if (t.type != Token.Type.LPAREN)
            throw ScriptError(t.pos, "Bad function definition: expected '(' after 'fn ${name}'")

        val argsDeclaration = parseArgsDeclaration()
        if (argsDeclaration == null || argsDeclaration.endTokenType != Token.Type.RPAREN)
            throw ScriptError(
                t.pos,
                "Bad function definition: expected valid argument declaration or () after 'fn ${name}'"
            )

        if (cc.current().type == Token.Type.COLON) parseTypeDeclaration()

        return inCodeContext(CodeContext.Function(name)) {

            // Here we should be at open body
            val fnStatements = if (isExtern)
                statement { raiseError("extern function not provided: $name") }
            else
                parseBlock()

            var closure: Scope? = null

            val fnBody = statement(t.pos) { callerContext ->
                callerContext.pos = start

                // restore closure where the function was defined, and making a copy of it
                // for local space. If there is no closure, we are in, say, class context where
                // the closure is in the class initialization and we needn't more:
                val context = closure?.let { ClosureScope(callerContext, it) }
                    ?: callerContext

                // load params from caller context
                argsDeclaration.assignToContext(context, callerContext.args, defaultAccessType = AccessType.Val)
                if (extTypeName != null) {
                    context.thisObj = callerContext.thisObj
                }
                fnStatements.execute(context)
            }
            val fnCreateStatement = statement(start) { context ->
                // we added fn in the context. now we must save closure
                // for the function, unless we're in the class scope:
                if (isStatic || parentContext !is CodeContext.ClassBody)
                    closure = context

                val annotatedFnBody = annotation?.invoke(context, ObjString(name), fnBody)
                    ?: fnBody

                extTypeName?.let { typeName ->
                    // class extension method
                    val type = context[typeName]?.value ?: context.raiseSymbolNotFound("class $typeName not found")
                    if (type !is ObjClass) context.raiseClassCastError("$typeName is not the class instance")
                    type.addFn(name, isOpen = true) {
                        // ObjInstance has a fixed instance scope, so we need to build a closure
                        (thisObj as? ObjInstance)?.let { i ->
                            annotatedFnBody.execute(ClosureScope(this, i.instanceScope))
                        }
                        // other classes can create one-time scope for this rare case:
                            ?: annotatedFnBody.execute(thisObj.autoInstanceScope(this))
                    }
                }
                // regular function/method
                    ?: context.addItem(name, false, annotatedFnBody, visibility)
                // as the function can be called from anywhere, we have
                // saved the proper context in the closure
                annotatedFnBody
            }
            if (isStatic) {
                currentInitScope += fnCreateStatement
                NopStatement
            } else
                fnCreateStatement
        }
    }

    private suspend fun parseBlock(skipLeadingBrace: Boolean = false): Statement {
        val startPos = cc.currentPos()
        if (!skipLeadingBrace) {
            val t = cc.next()
            if (t.type != Token.Type.LBRACE)
                throw ScriptError(t.pos, "Expected block body start: {")
        }
        val block = parseScript()
        return statement(startPos) {
            // block run on inner context:
            block.execute(if (it.skipScopeCreation) it else it.copy(startPos))
        }.also {
            val t1 = cc.next()
            if (t1.type != Token.Type.RBRACE)
                throw ScriptError(t1.pos, "unbalanced braces: expected block body end: }")
        }
    }

    private suspend fun parseVarDeclaration(
        isMutable: Boolean,
        visibility: Visibility,
        @Suppress("UNUSED_PARAMETER") isOpen: Boolean = false,
        isStatic: Boolean = false
    ): Statement {
        val nameToken = cc.next()
        val start = nameToken.pos
        if (nameToken.type != Token.Type.ID)
            throw ScriptError(nameToken.pos, "Expected identifier here")
        val name = nameToken.value

        val eqToken = cc.next()
        var setNull = false

        val isDelegate = if (eqToken.isId("by")) {
            true
        } else {
            if (eqToken.type != Token.Type.ASSIGN) {
                if (!isMutable)
                    throw ScriptError(start, "val must be initialized")
                else {
                    cc.previous()
                    setNull = true
                }
            }
            false
        }

        val initialExpression = if (setNull) null
        else parseStatement(true)
            ?: throw ScriptError(eqToken.pos, "Expected initializer expression")

        if (isStatic) {
            // find objclass instance: this is tricky: this code executes in object initializer,
            // when creating instance, but we need to execute it in the class initializer which
            // is missing as for now. Add it to the compiler context?

//            if (isDelegate) throw ScriptError(start, "static delegates are not yet implemented")
            currentInitScope += statement {
                val initValue = initialExpression?.execute(this)?.byValueCopy() ?: ObjNull
                (thisObj as ObjClass).createClassField(name, initValue, isMutable, visibility, pos)
                addItem(name, isMutable, initValue, visibility, ObjRecord.Type.Field)
                ObjVoid
            }
            return NopStatement
        }

        return statement(nameToken.pos) { context ->
            if (context.containsLocal(name))
                throw ScriptError(nameToken.pos, "Variable $name is already defined")

            if (isDelegate) {
                TODO()
//                println("initial expr = $initialExpression")
//                val initValue =
//                    (initialExpression?.execute(context.copy(Arguments(ObjString(name)))) as? Statement)
//                        ?.execute(context.copy(Arguments(ObjString(name))))
//                    ?: context.raiseError("delegate initialization required")
//                println("delegate init: $initValue")
//                if (!initValue.isInstanceOf(ObjArray))
//                    context.raiseIllegalArgument("delegate initialized must be an array")
//                val s = initValue.getAt(context, 1)
//                val setter = if (s == ObjNull) statement { raiseNotImplemented("setter is not provided") }
//                else (s as? Statement) ?: context.raiseClassCastError("setter must be a callable")
//                ObjDelegate(
//                    (initValue.getAt(context, 0) as? Statement)
//                        ?: context.raiseClassCastError("getter must be a callable"), setter
//                ).also {
//                    context.addItem(name, isMutable, it, visibility, recordType = ObjRecord.Type.Field)
//                }
            } else {
                // init value could be a val; when we initialize by-value type var with it, we need to
                // create a separate copy:
                val initValue = initialExpression?.execute(context)?.byValueCopy() ?: ObjNull
                context.addItem(name, isMutable, initValue, visibility, recordType = ObjRecord.Type.Field)
                initValue
            }
        }
    }

    data class Operator(
        val tokenType: Token.Type,
        val priority: Int, val arity: Int = 2,
        val generate: (Pos, Accessor, Accessor) -> Accessor
    ) {
//        fun isLeftAssociative() = tokenType != Token.Type.OR && tokenType != Token.Type.AND

        companion object {
            fun simple(tokenType: Token.Type, priority: Int, f: suspend (Scope, Obj, Obj) -> Obj): Operator =
                Operator(tokenType, priority, 2) { _: Pos, a: Accessor, b: Accessor ->
                    Accessor { f(it, a.getter(it).value, b.getter(it).value).asReadonly }
                }
        }

    }

    companion object {

        suspend fun compile(source: Source, importManager: ImportProvider): Script {
            return Compiler(CompilerContext(parseLyng(source)), importManager).parseScript()
        }

        private var lastPriority = 0
        val allOps = listOf(
            // assignments, lowest priority
            Operator(Token.Type.ASSIGN, lastPriority) { pos, a, b ->
                Accessor {
                    val value = b.getter(it).value
                    val access = a.getter(it)
                    if (!access.isMutable) throw ScriptError(pos, "cannot assign to immutable variable")
                    if (access.value.assign(it, value) == null)
                        a.setter(pos)(it, value)
                    value.asReadonly
                }
            },
            Operator(Token.Type.PLUSASSIGN, lastPriority) { pos, a, b ->
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
            Operator(Token.Type.MINUSASSIGN, lastPriority) { pos, a, b ->
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
            Operator(Token.Type.STARASSIGN, lastPriority) { pos, a, b ->
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
            Operator(Token.Type.SLASHASSIGN, lastPriority) { pos, a, b ->
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
            Operator(Token.Type.PERCENTASSIGN, lastPriority) { pos, a, b ->
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
            Operator.simple(Token.Type.OR, ++lastPriority) { ctx, a, b -> a.logicalOr(ctx, b) },
            // logical 2
            Operator.simple(Token.Type.AND, ++lastPriority) { ctx, a, b -> a.logicalAnd(ctx, b) },
            // bitwise or 2
            // bitwise and 3
            // equality/not equality 4
            Operator.simple(Token.Type.EQARROW, ++lastPriority) { _, a, b -> ObjMapEntry(a, b) },
            //
            Operator.simple(Token.Type.EQ, ++lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) == 0) },
            Operator.simple(Token.Type.NEQ, lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) != 0) },
            Operator.simple(Token.Type.REF_EQ, lastPriority) { _, a, b -> ObjBool(a === b) },
            Operator.simple(Token.Type.REF_NEQ, lastPriority) { _, a, b -> ObjBool(a !== b) },
            // relational <=,... 5
            Operator.simple(Token.Type.LTE, ++lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) <= 0) },
            Operator.simple(Token.Type.LT, lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) < 0) },
            Operator.simple(Token.Type.GTE, lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) >= 0) },
            Operator.simple(Token.Type.GT, lastPriority) { c, a, b -> ObjBool(a.compareTo(c, b) > 0) },
            // in, is:
            Operator.simple(Token.Type.IN, lastPriority) { c, a, b -> ObjBool(b.contains(c, a)) },
            Operator.simple(Token.Type.NOTIN, lastPriority) { c, a, b -> ObjBool(!b.contains(c, a)) },
            Operator.simple(Token.Type.IS, lastPriority) { _, a, b -> ObjBool(a.isInstanceOf(b)) },
            Operator.simple(Token.Type.NOTIS, lastPriority) { _, a, b -> ObjBool(!a.isInstanceOf(b)) },

            Operator(Token.Type.ELVIS, ++lastPriority, 2) { _: Pos, a: Accessor, b: Accessor ->
                Accessor {
                    val aa = a.getter(it).value
                    (
                            if (aa != ObjNull) aa
                            else b.getter(it).value
                            ).asReadonly
                }
            },

            // shuttle <=> 6
            Operator.simple(Token.Type.SHUTTLE, ++lastPriority) { c, a, b ->
                ObjInt(a.compareTo(c, b).toLong())
            },
            // bit shifts 7
            Operator.simple(Token.Type.PLUS, ++lastPriority) { ctx, a, b -> a.plus(ctx, b) },
            Operator.simple(Token.Type.MINUS, lastPriority) { ctx, a, b -> a.minus(ctx, b) },

            Operator.simple(Token.Type.STAR, ++lastPriority) { ctx, a, b -> a.mul(ctx, b) },
            Operator.simple(Token.Type.SLASH, lastPriority) { ctx, a, b -> a.div(ctx, b) },
            Operator.simple(Token.Type.PERCENT, lastPriority) { ctx, a, b -> a.mod(ctx, b) },
        )

//        private val assigner = allOps.first { it.tokenType == Token.Type.ASSIGN }
//
//        fun performAssignment(context: Context, left: Accessor, right: Accessor) {
//            assigner.generate(context.pos, left, right)
//        }

        val lastLevel = lastPriority + 1

        val byLevel: List<Map<Token.Type, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }.associateBy { it.tokenType }
        }

        suspend fun compile(code: String): Script = compile(Source("<eval>", code), Script.defaultImportManager)

        /**
         * The keywords that stop processing of expression term
         */
        val stopKeywords =
            setOf("do", "break", "continue", "return", "if", "when", "do", "while", "for", "class")
    }
}

suspend fun eval(code: String) = Compiler.compile(code).execute()

