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

    // Stack of parameter-to-slot plans for current function being parsed (by declaration index)
    private val paramSlotPlanStack = mutableListOf<Map<String, Int>>()
    private val currentParamSlotPlan: Map<String, Int>?
        get() = paramSlotPlanStack.lastOrNull()

    // Track identifiers known to be locals/parameters in the current function for fast local emission
    private val localNamesStack = mutableListOf<MutableSet<String>>()
    private val currentLocalNames: MutableSet<String>?
        get() = localNamesStack.lastOrNull()

    // Track declared local variables count per function for precise capacity hints
    private val localDeclCountStack = mutableListOf<Int>()
    private val currentLocalDeclCount: Int
        get() = localDeclCountStack.lastOrNull() ?: 0

    private inline fun <T> withLocalNames(names: Set<String>, block: () -> T): T {
        localNamesStack.add(names.toMutableSet())
        return try { block() } finally { localNamesStack.removeLast() }
    }

    private fun declareLocalName(name: String) {
        // Add to current function's local set; only count if it was newly added (avoid duplicates)
        val added = currentLocalNames?.add(name) == true
        if (added && localDeclCountStack.isNotEmpty()) {
            localDeclCountStack[localDeclCountStack.lastIndex] = currentLocalDeclCount + 1
        }
    }

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
        // Track locals at script level for fast local refs
        return withLocalNames(emptySet()) {
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
            Script(start, statements)
        }
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
        return parseExpressionLevel()?.let { a -> statement(pos) { a.get(it).value } }
    }

    private suspend fun parseExpressionLevel(level: Int = 0): ObjRef? {
        if (level == lastLevel)
            return parseTerm()
        var lvalue: ObjRef? = parseExpressionLevel(level + 1) ?: return null

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

    private suspend fun parseTerm(): ObjRef? {
        var operand: ObjRef? = null

        // newlines _before_
        cc.skipWsTokens()

        while (true) {
            val t = cc.next()
            val startPos = t.pos
            when (t.type) {
//                Token.Type.NEWLINE, Token.Type.SINLGE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT-> {
//                    continue
//                }

                // very special case chained calls: call()<NL>.call2 {}.call3()
                Token.Type.NEWLINE -> {
                    val saved = cc.savePos()
                    if (cc.peekNextNonWhitespace().type == Token.Type.DOT) {
                        // chained call continue from it
                        continue
                    } else {
                        // restore position and stop parsing as a term:
                        cc.restorePos(saved)
                        cc.previous()
                        return operand
                    }
                }

                Token.Type.SEMICOLON, Token.Type.EOF, Token.Type.RBRACE, Token.Type.COMMA -> {
                    cc.previous()
                    return operand
                }

                Token.Type.NOT -> {
                    if (operand != null) throw ScriptError(t.pos, "unexpected operator not '!' ")
                    val op = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                    operand = UnaryOpRef(UnaryOp.NOT, op)
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
                                    val parsed = parseArgs()
                                    val args = parsed.first
                                    val tailBlock = parsed.second
                                    isCall = true
                                    operand = MethodCallRef(left, next.value, args, tailBlock, isOptional)
                                }


                                Token.Type.LBRACE, Token.Type.NULL_COALESCE_BLOCKINVOKE -> {
                                    // single lambda arg, like assertThrows { ... }
                                    cc.next()
                                    isCall = true
                                                val lambda = parseLambdaExpression()
                                    val argStmt = statement { lambda.get(this).value }
                                    val args = listOf(ParsedArgument(argStmt, next.pos))
                                    operand = MethodCallRef(left, next.value, args, true, isOptional)
                                }

                                else -> {}
                            }
                        }
                        if (!isCall) {
                            operand = FieldRef(left, next.value, isOptional)
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
                        operand = StatementRef(statement)
                        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                        cc.skipTokenOfType(Token.Type.RPAREN, "missing ')'")
                    }
                }

                Token.Type.LBRACKET, Token.Type.NULL_COALESCE_INDEX -> {
                    operand?.let { left ->
                        // array access via ObjRef
                        val isOptional = t.type == Token.Type.NULL_COALESCE_INDEX
                        val index = parseStatement() ?: throw ScriptError(t.pos, "Expecting index expression")
                        cc.skipTokenOfType(Token.Type.RBRACKET, "missing ']' at the end of the list literal")
                        operand = IndexRef(left, StatementRef(index), isOptional)
                    } ?: run {
                        // array literal
                        val entries = parseArrayLiteral()
                        // build list literal via ObjRef node (no per-access lambdas)
                        operand = ListLiteralRef(entries)
                    }
                }

                Token.Type.ID -> {
                    // there could be terminal operators or keywords:// variable to read or like
                    when (t.value) {
                        in stopKeywords -> {
                            if (operand != null) throw ScriptError(t.pos, "unexpected keyword")
                            cc.previous()
                            val s = parseStatement() ?: throw ScriptError(t.pos, "Expecting valid statement")
                            operand = StatementRef(s)
                        }

                        "else", "break", "continue" -> {
                            cc.previous()
                            return operand

                        }

                        "throw" -> {
                            val s = parseThrowStatement()
                            operand = StatementRef(s)
                        }

                        else -> operand?.let { left ->
                            // selector: <lvalue>, '.' , <id>
                            // we replace operand with selector code, that
                            // is RW:
                            operand = FieldRef(left, t.value, false)
                        } ?: run {
                            // variable to read or like
                            cc.previous()
                            operand = parseAccessor()
                        }
                    }
                }

                Token.Type.PLUS2 -> {
                    // ++ (post if operand exists, pre otherwise)
                    operand = operand?.let { left ->
                        IncDecRef(left, isIncrement = true, isPost = true, atPos = startPos)
                    } ?: run {
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        IncDecRef(next, isIncrement = true, isPost = false, atPos = startPos)
                    }
                }

                Token.Type.MINUS2 -> {
                    // -- (post if operand exists, pre otherwise)
                    operand = operand?.let { left ->
                        IncDecRef(left, isIncrement = false, isPost = true, atPos = startPos)
                    } ?: run {
                        val next = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression")
                        IncDecRef(next, isIncrement = false, isPost = false, atPos = startPos)
                    }
                }

                Token.Type.DOTDOT, Token.Type.DOTDOTLT -> {
                    // range operator
                    val isEndInclusive = t.type == Token.Type.DOTDOT
                    val left = operand
                    // if it is an open end range, then the end of line could be here that we do not want
                    // to skip in parseExpression:
                    val current = cc.current()
                    val right =
                        if (current.type == Token.Type.NEWLINE || current.type == Token.Type.SINLGE_LINE_COMMENT)
                            null
                        else
                            parseExpression()
                    operand = RangeRef(
                        left,
                        right?.let { StatementRef(it) },
                        isEndInclusive
                    )
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
    private suspend fun parseLambdaExpression(): ObjRef {
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

        return ValueFnRef { x ->
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

    private fun parseScopeOperator(operand: ObjRef?): ObjRef {
        // implement global scope maybe?
        if (operand == null) throw ScriptError(cc.next().pos, "Expecting expression before ::")
        val t = cc.next()
        if (t.type != Token.Type.ID) throw ScriptError(t.pos, "Expecting ID after ::")
        return when (t.value) {
            "class" -> ValueFnRef { scope ->
                operand.get(scope).value.objClass.asReadonly
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

        cc.skipWsTokens()

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
                    when (val tt = cc.nextNonWhitespace().type) {
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
                // transform ObjRef to the callable value
                statement {
                    callableAccessor.get(this).value
                },
                end.pos
            )
            lastBlockArgument = true
        } else
            cc.restorePos(pos)
        return args to lastBlockArgument
    }


    private suspend fun parseFunctionCall(
        left: ObjRef,
        blockArgument: Boolean,
        isOptional: Boolean
    ): ObjRef {
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
        return CallRef(left, args, detectedBlockArgument, isOptional)
    }

    private suspend fun parseAccessor(): ObjRef? {
        // could be: literal
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.REAL, Token.Type.HEX -> {
                cc.previous()
                val n = parseNumber(true)
                ConstRef(n.asReadonly)
            }

            Token.Type.STRING -> ConstRef(ObjString(t.value).asReadonly)

            Token.Type.CHAR -> ConstRef(ObjChar(t.value[0]).asReadonly)

            Token.Type.PLUS -> {
                val n = parseNumber(true)
                ConstRef(n.asReadonly)
            }

            Token.Type.MINUS -> {
                parseNumberOrNull(false)?.let { n ->
                    ConstRef(n.asReadonly)
                } ?: run {
                    val n = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression after unary minus")
                    UnaryOpRef(UnaryOp.NEGATE, n)
                }
            }

            Token.Type.ID -> {
                when (t.value) {
                    "void" -> ConstRef(ObjVoid.asReadonly)
                    "null" -> ConstRef(ObjNull.asReadonly)
                    "true" -> ConstRef(ObjTrue.asReadonly)
                    "false" -> ConstRef(ObjFalse.asReadonly)
                    else -> if (PerfFlags.EMIT_FAST_LOCAL_REFS && (currentLocalNames?.contains(t.value) == true))
                        FastLocalVarRef(t.value, t.pos)
                    else LocalVarRef(t.value, t.pos)
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

    @Suppress("SameParameterValue")
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
            (fn.execute(scope.createChildScope(Arguments(args))) as? Statement)
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
        var t = cc.nextNonWhitespace()
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
                    t = cc.nextNonWhitespace()

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
                        val catchContext = this.createChildScope(pos = cdata.catchVar.pos)
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
            val t = cc.nextNonWhitespace()
            when (t.type) {
                Token.Type.ID -> {
                    names += t.value
                    val t1 = cc.nextNonWhitespace()
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
                    val classScope = createChildScope(newThisObj = newClass)
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

            // Expose the loop variable name to the parser so identifiers inside the loop body
            // can be emitted as FastLocalVarRef when enabled.
            val namesForLoop = (currentLocalNames?.toSet() ?: emptySet()) + tVar.value
            val (canBreak, body, elseStatement) = withLocalNames(namesForLoop) {
                val loopParsed = cc.parseLoop {
                    parseStatement() ?: throw ScriptError(start, "Bad for statement: expected loop body")
                }
                // possible else clause
                cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)
                val elseStmt = if (cc.next().let { it.type == Token.Type.ID && it.value == "else" }) {
                    parseStatement()
                } else {
                    cc.previous()
                    null
                }
                Triple(loopParsed.first, loopParsed.second, elseStmt)
            }

            return statement(body.pos) { cxt ->
                val forContext = cxt.createChildScope(start)

                // loop var: StoredObject
                val loopSO = forContext.addItem(tVar.value, true, ObjNull)

                // insofar we suggest source object is enumerable. Later we might need to add checks
                val sourceObj = source.execute(forContext)

                if (sourceObj is ObjRange && sourceObj.isIntRange && PerfFlags.PRIMITIVE_FASTOPS) {
                    loopIntRange(
                        forContext,
                        sourceObj.start!!.toLong(),
                        if (sourceObj.isEndInclusive)
                            sourceObj.end!!.toLong() + 1
                        else
                            sourceObj.end!!.toLong(),
                        loopSO,
                        body,
                        elseStatement,
                        label,
                        canBreak
                    )
                } else if (sourceObj.isInstanceOf(ObjIterable)) {
                    loopIterable(forContext, sourceObj, loopSO, body, elseStatement, label, canBreak)
                } else {
                    val size = runCatching { sourceObj.invokeInstanceMethod(forContext, "size").toInt() }
                        .getOrElse {
                            throw ScriptError(
                                tOp.pos,
                                "object is not enumerable: no size in $sourceObj",
                                it
                            )
                        }

                    var result: Obj = ObjVoid
                    var breakCaught = false

                    if (size > 0) {
                        var current = runCatching { sourceObj.getAt(forContext, ObjInt(0)) }
                            .getOrElse {
                                throw ScriptError(
                                    tOp.pos,
                                    "object is not enumerable: no index access for ${sourceObj.inspect(cxt)}",
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
                        result = elseStatement.execute(cxt)
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
        forScope: Scope, start: Long, end: Long, loopVar: ObjRecord,
        body: Statement, elseStatement: Statement?, label: String?, catchBreak: Boolean
    ): Obj {
        var result: Obj = ObjVoid
        val iVar = ObjInt(0)
        loopVar.value = iVar
        if (catchBreak) {
            for (i in start..<end) {
                iVar.value = i//.toLong()
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
            for (i in start..<end) {
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
            while (true) {
                doScope = it.createChildScope().apply { skipScopeCreation = true }
                try {
                    result = body.execute(doScope)
                } catch (e: LoopBreakContinueException) {
                    if (e.label == label || e.label == null) {
                        if (!e.doContinue) {
                            result = e.result
                            wasBroken = true
                            break
                        }
                        // for continue: just fall through to condition check below
                    } else {
                        // Not our label, let outer loops handle it
                        throw e
                    }
                }
                if (!condition.execute(doScope).toBool()) break
            }
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
        val t2 = cc.nextNonWhitespace()

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

            val paramNames: Set<String> = argsDeclaration.params.map { it.name }.toSet()

            // Parse function body while tracking declared locals to compute precise capacity hints
            val fnLocalDeclStart = currentLocalDeclCount
            localDeclCountStack.add(0)
            val fnStatements = if (isExtern)
                statement { raiseError("extern function not provided: $name") }
            else
                withLocalNames(paramNames) { parseBlock() }
            // Capture and pop the local declarations count for this function
            val fnLocalDecls = localDeclCountStack.removeLastOrNull() ?: 0

            var closure: Scope? = null

            val fnBody = statement(t.pos) { callerContext ->
                callerContext.pos = start

                // restore closure where the function was defined, and making a copy of it
                // for local space. If there is no closure, we are in, say, class context where
                // the closure is in the class initialization and we needn't more:
                val context = closure?.let { ClosureScope(callerContext, it) }
                    ?: callerContext

                // Capacity hint: parameters + declared locals + small overhead
                val capacityHint = paramNames.size + fnLocalDecls + 4
                context.hintLocalCapacity(capacityHint)

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
            block.execute(if (it.skipScopeCreation) it else it.createChildScope(startPos))
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

        // Register the local name at compile time so that subsequent identifiers can be emitted as fast locals
        if (!isStatic) declareLocalName(name)

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

            // Register the local name so subsequent identifiers can be emitted as fast locals
            if (!isStatic) declareLocalName(name)

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
        val generate: (Pos, ObjRef, ObjRef) -> ObjRef
    ) {
//        fun isLeftAssociative() = tokenType != Token.Type.OR && tokenType != Token.Type.AND

        companion object {}

    }

    companion object {

        suspend fun compile(source: Source, importManager: ImportProvider): Script {
            return Compiler(CompilerContext(parseLyng(source)), importManager).parseScript()
        }

        private var lastPriority = 0
        val allOps = listOf(
            // assignments, lowest priority
            Operator(Token.Type.ASSIGN, lastPriority) { pos, a, b ->
                AssignRef(a, b, pos)
            },
            Operator(Token.Type.PLUSASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.PLUS, a, b, pos)
            },
            Operator(Token.Type.MINUSASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.MINUS, a, b, pos)
            },
            Operator(Token.Type.STARASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.STAR, a, b, pos)
            },
            Operator(Token.Type.SLASHASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.SLASH, a, b, pos)
            },
            Operator(Token.Type.PERCENTASSIGN, lastPriority) { pos, a, b ->
                AssignOpRef(BinOp.PERCENT, a, b, pos)
            },
            // logical 1
            Operator(Token.Type.OR, ++lastPriority) { _, a, b ->
                LogicalOrRef(a, b)
            },
            // logical 2
            Operator(Token.Type.AND, ++lastPriority) { _, a, b ->
                LogicalAndRef(a, b)
            },
            // equality/not equality and related
            Operator(Token.Type.EQARROW, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.EQARROW, a, b)
            },
            Operator(Token.Type.EQ, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.EQ, a, b)
            },
            Operator(Token.Type.NEQ, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NEQ, a, b)
            },
            Operator(Token.Type.REF_EQ, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.REF_EQ, a, b)
            },
            Operator(Token.Type.REF_NEQ, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.REF_NEQ, a, b)
            },
            Operator(Token.Type.MATCH, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.MATCH, a, b)
            },
            Operator(Token.Type.NOTMATCH, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTMATCH, a, b)
            },
            // relational <=,...
            Operator(Token.Type.LTE, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.LTE, a, b)
            },
            Operator(Token.Type.LT, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.LT, a, b)
            },
            Operator(Token.Type.GTE, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.GTE, a, b)
            },
            Operator(Token.Type.GT, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.GT, a, b)
            },
            // in, is:
            Operator(Token.Type.IN, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.IN, a, b)
            },
            Operator(Token.Type.NOTIN, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTIN, a, b)
            },
            Operator(Token.Type.IS, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.IS, a, b)
            },
            Operator(Token.Type.NOTIS, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.NOTIS, a, b)
            },

            Operator(Token.Type.ELVIS, ++lastPriority, 2) { _, a, b ->
                ElvisRef(a, b)
            },

            // shuttle <=>
            Operator(Token.Type.SHUTTLE, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.SHUTTLE, a, b)
            },
            // arithmetic
            Operator(Token.Type.PLUS, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.PLUS, a, b)
            },
            Operator(Token.Type.MINUS, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.MINUS, a, b)
            },
            Operator(Token.Type.STAR, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.STAR, a, b)
            },
            Operator(Token.Type.SLASH, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.SLASH, a, b)
            },
            Operator(Token.Type.PERCENT, lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.PERCENT, a, b)
            },
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

