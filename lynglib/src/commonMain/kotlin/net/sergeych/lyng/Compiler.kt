/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
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

import net.sergeych.lyng.Compiler.Companion.compile
import net.sergeych.lyng.miniast.*
import net.sergeych.lyng.obj.*
import net.sergeych.lyng.pacman.ImportManager
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
    @Suppress("unused")
    private val paramSlotPlanStack = mutableListOf<Map<String, Int>>()
//    private val currentParamSlotPlan: Map<String, Int>?
//        get() = paramSlotPlanStack.lastOrNull()

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
        return try {
            block()
        } finally {
            localNamesStack.removeLast()
        }
    }

    private fun declareLocalName(name: String) {
        // Add to current function's local set; only count if it was newly added (avoid duplicates)
        val added = currentLocalNames?.add(name) == true
        if (added && localDeclCountStack.isNotEmpty()) {
            localDeclCountStack[localDeclCountStack.lastIndex] = currentLocalDeclCount + 1
        }
    }

    var packageName: String? = null

    class Settings(
        val miniAstSink: MiniAstSink? = null,
    )

    // Optional sink for mini-AST streaming (null by default, zero overhead when not used)
    private val miniSink: MiniAstSink? = settings.miniAstSink

    // --- Doc-comment collection state (for immediate preceding declarations) ---
    private val pendingDocLines = mutableListOf<String>()
    private var pendingDocStart: Pos? = null
    private var prevWasComment: Boolean = false

    private fun stripCommentLexeme(raw: String): String {
        return when {
            raw.startsWith("//") -> raw.removePrefix("//")
            raw.startsWith("/*") && raw.endsWith("*/") -> {
                val inner = raw.substring(2, raw.length - 2)
                // Trim leading "*" prefixes per line like Javadoc style
                inner.lines().joinToString("\n") { line ->
                    val t = line.trimStart()
                    if (t.startsWith("*")) t.removePrefix("*").trimStart() else line
                }
            }

            else -> raw
        }
    }

    private var anonCounter = 0
    private fun generateAnonName(pos: Pos): String {
        return "${"$"}${"Anon"}_${pos.line+1}_${pos.column}_${++anonCounter}"
    }

    private fun pushPendingDocToken(t: Token) {
        val s = stripCommentLexeme(t.value)
        if (pendingDocStart == null) pendingDocStart = t.pos
        pendingDocLines += s
        prevWasComment = true
    }

    private fun clearPendingDoc() {
        pendingDocLines.clear()
        pendingDocStart = null
        prevWasComment = false
    }

    private fun consumePendingDoc(): MiniDoc? {
        if (pendingDocLines.isEmpty()) return null
        val start = pendingDocStart ?: cc.currentPos()
        val doc = MiniDoc.parse(MiniRange(start, start), pendingDocLines)
        clearPendingDoc()
        return doc
    }

    private fun nextNonWhitespace(): Token {
        while (true) {
            val t = cc.next()
            when (t.type) {
                Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> {
                    pushPendingDocToken(t)
                }

                Token.Type.NEWLINE -> {
                    if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                }

                Token.Type.EOF -> return t
                else -> return t
            }
        }
    }

    // Set just before entering a declaration parse, taken from keyword token position
    private var pendingDeclStart: Pos? = null
    private var pendingDeclDoc: MiniDoc? = null

    private val initStack = mutableListOf<MutableList<Statement>>()

    val currentInitScope: MutableList<Statement>
        get() =
            initStack.lastOrNull() ?: cc.syntaxError("no initialization scope exists here")

    private fun pushInitScope(): MutableList<Statement> = mutableListOf<Statement>().also { initStack.add(it) }

    private fun popInitScope(): MutableList<Statement> = initStack.removeLast()

    private val codeContexts = mutableListOf<CodeContext>(CodeContext.Module(null))

    // Last parsed block range (for Mini-AST function body attachment)
    private var lastParsedBlockRange: MiniRange? = null

    private suspend fun <T> inCodeContext(context: CodeContext, f: suspend () -> T): T {
        codeContexts.add(context)
        try {
            val res = f()
            if (context is CodeContext.ClassBody) {
                if (context.pendingInitializations.isNotEmpty()) {
                    val (name, pos) = context.pendingInitializations.entries.first()
                    throw ScriptError(pos, "val '$name' must be initialized in the class body or init block")
                }
            }
            return res
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
            // Notify sink about script start
            miniSink?.onScriptStart(start)
            do {
                val t = cc.current()
                if (t.type == Token.Type.NEWLINE || t.type == Token.Type.SINGLE_LINE_COMMENT || t.type == Token.Type.MULTILINE_COMMENT) {
                    when (t.type) {
                        Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> pushPendingDocToken(t)
                        Token.Type.NEWLINE -> {
                            // A standalone newline not immediately following a comment resets doc buffer
                            if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                        }
                        else -> {}
                    }
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
                            // Emit MiniImport with approximate per-segment ranges
                            run {
                                try {
                                    val parts = name.split('.')
                                    if (parts.isNotEmpty()) {
                                        var col = pos.column
                                        val segs = parts.map { p ->
                                            val start = Pos(pos.source, pos.line, col)
                                            val end = Pos(pos.source, pos.line, col + p.length)
                                            col += p.length + 1 // account for following '.' between segments
                                            MiniImport.Segment(
                                                p,
                                                MiniRange(start, end)
                                            )
                                        }
                                        val lastEnd = segs.last().range.end
                                        miniSink?.onImport(
                                            MiniImport(
                                                MiniRange(pos, lastEnd),
                                                segs
                                            )
                                        )
                                    }
                                } catch (_: Throwable) {
                                    // best-effort; ignore import mini emission failures
                                }
                            }
                            val module = importManager.prepareImport(pos, name, null)
                            statements += statement {
                                module.importInto(this, null)
                                ObjVoid
                            }
                            continue
                        }
                    }
                    // Fast-path: top-level function declarations. Handle here to ensure
                    // Mini-AST emission even if qualifier matcher paths change.
                    if (t.value == "fun" || t.value == "fn") {
                        // Consume the keyword and delegate to the function parser
                        cc.next()
                        pendingDeclStart = t.pos
                        pendingDeclDoc = consumePendingDoc()
                        val st = parseFunctionDeclaration(isExtern = false, isStatic = false)
                        statements += st
                        continue
                    }
                }
                val s = parseStatement(braceMeansLambda = true)?.also {
                    statements += it
                }
                if (s == null) {
                    when (t.type) {
                        Token.Type.RBRACE, Token.Type.EOF, Token.Type.SEMICOLON -> {}
                        else ->
                            throw ScriptError(t.pos, "unexpected `${t.value}` here")
                    }
                    break
                }

            } while (true)
            Script(start, statements)
        }.also {
            // Best-effort script end notification (use current position)
            miniSink?.onScriptEnd(
                cc.currentPos(),
                MiniScript(MiniRange(start, cc.currentPos()))
            )
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
    private var isTransientFlag: Boolean = false
    private var lastLabel: String? = null

    private suspend fun parseStatement(braceMeansLambda: Boolean = false): Statement? {
        lastAnnotation = null
        lastLabel = null
        isTransientFlag = false
        while (true) {
            val t = cc.next()
            return when (t.type) {
                Token.Type.ID, Token.Type.OBJECT -> {
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
                    val label = t.value
                    if (label == "Transient") {
                        isTransientFlag = true
                        continue
                    }
                    if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        lastLabel = label
                    }
                    lastAnnotation = parseAnnotation(t)
                    continue
                }

                Token.Type.LABEL -> continue
                Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT -> {
                    pushPendingDocToken(t)
                    continue
                }

                Token.Type.NEWLINE -> {
                    if (!prevWasComment) clearPendingDoc() else prevWasComment = false
                    continue
                }

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
        return parseExpressionLevel()?.let { a -> statement(pos) { a.evalValue(it) } }
    }

    private suspend fun parseExpressionLevel(level: Int = 0): ObjRef? {
        if (level == lastLevel)
            return parseTerm()
        var lvalue: ObjRef? = parseExpressionLevel(level + 1) ?: return null

        while (true) {
            val opToken = cc.next()
            val op = byLevel[level][opToken.type]
            if (op == null) {
                // handle ternary conditional at the top precedence level only: a ? b : c
                if (opToken.type == Token.Type.QUESTION && level == 0) {
                    val thenRef = parseExpressionLevel(level + 1)
                        ?: throw ScriptError(opToken.pos, "Expecting expression after '?'")
                    val colon = cc.next()
                    if (colon.type != Token.Type.COLON) colon.raiseSyntax("missing ':'")
                    val elseRef = parseExpressionLevel(level + 1)
                        ?: throw ScriptError(colon.pos, "Expecting expression after ':'")
                    lvalue = ConditionalRef(lvalue!!, thenRef, elseRef)
                    continue
                }
                cc.previous()
                break
            }

            val rvalue = parseExpressionLevel(level + 1)
                ?: throw ScriptError(opToken.pos, "Expecting expression")

            val res = op.generate(opToken.pos, lvalue!!, rvalue)
            if (opToken.type == Token.Type.ASSIGN) {
                val ctx = codeContexts.lastOrNull()
                if (ctx is CodeContext.ClassBody) {
                    val target = lvalue
                    val name = when (target) {
                        is LocalVarRef -> target.name
                        is FastLocalVarRef -> target.name
                        is FieldRef -> if (target.target is LocalVarRef && target.target.name == "this") target.name else null
                        else -> null
                    }
                    if (name != null) ctx.pendingInitializations.remove(name)
                }
            }
            lvalue = res
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
//                Token.Type.NEWLINE, Token.Type.SINGLE_LINE_COMMENT, Token.Type.MULTILINE_COMMENT-> {
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

                Token.Type.BITNOT -> {
                    if (operand != null) throw ScriptError(t.pos, "unexpected operator '~'")
                    val op = parseTerm() ?: throw ScriptError(t.pos, "Expecting expression after '~'")
                    operand = UnaryOpRef(UnaryOp.BITNOT, op)
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
                            if (t.value == "init" && !(codeContexts.lastOrNull() is CodeContext.ClassBody && cc.peekNextNonWhitespace().type == Token.Type.LBRACE)) {
                                // Soft keyword: init is only a keyword in class body when followed by {
                                cc.previous()
                                operand = parseAccessor()
                            } else {
                                if (operand != null) throw ScriptError(t.pos, "unexpected keyword")
                                // Allow certain statement-like constructs to act as expressions
                                // when they appear in expression position (e.g., `if (...) ... else ...`).
                                // Other keywords should be handled by the outer statement parser.
                                when (t.value) {
                                    "if" -> {
                                        val s = parseIfStatement()
                                        operand = StatementRef(s)
                                    }

                                    "when" -> {
                                        val s = parseWhenStatement()
                                        operand = StatementRef(s)
                                    }

                                    else -> {
                                        // Do not consume the keyword as part of a term; backtrack
                                        // and return null so outer parser handles it.
                                        cc.previous()
                                        return null
                                    }
                                }
                            }
                        }

                        "else", "break", "continue" -> {
                            cc.previous()
                            return operand

                        }

                        "throw" -> {
                            val s = parseThrowStatement(t.pos)
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
                        if (current.type == Token.Type.NEWLINE || current.type == Token.Type.SINGLE_LINE_COMMENT)
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
                        // Trailing block-argument function call: the leading '{' is already consumed,
                        // and the lambda must be parsed as a single argument BEFORE any following
                        // selectors like ".foo" are considered. Do NOT rewind here, otherwise
                        // the expression parser may capture ".foo" as part of the lambda expression.
                        parseFunctionCall(
                            left,
                            blockArgument = true,
                            isOptional = t.type == Token.Type.NULL_COALESCE_BLOCKINVOKE
                        )
                    } ?: run {
                        // Disambiguate between lambda and map literal.
                        // Heuristic: if there is a top-level '->' before the closing '}', it's a lambda.
                        // Otherwise, try to parse a map literal; if it fails, fall back to lambda.
                        val isLambda = hasTopLevelArrowBeforeRbrace()
                        if (!isLambda) {
                            parseMapLiteralOrNull() ?: parseLambdaExpression()
                        } else parseLambdaExpression()
                    }
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
        val label = lastLabel
        val argsDeclaration = parseArgsDeclaration()
        if (argsDeclaration != null && argsDeclaration.endTokenType != Token.Type.ARROW)
            throw ScriptError(
                startPos,
                "lambda must have either valid arguments declaration with '->' or no arguments"
            )

        val paramNames = argsDeclaration?.params?.map { it.name } ?: emptyList()

        label?.let { cc.labels.add(it) }
        val body = inCodeContext(CodeContext.Function("<lambda>")) {
            withLocalNames(paramNames.toSet()) {
                parseBlock(skipLeadingBrace = true)
            }
        }
        label?.let { cc.labels.remove(it) }

        return ValueFnRef { closureScope ->
            statement(body.pos) { scope ->
                // and the source closure of the lambda which might have other thisObj.
                val context = scope.applyClosure(closureScope)
                // Execute lambda body in a closure-aware context. Blocks inside the lambda
                // will create child scopes as usual, so re-declarations inside loops work.
                if (argsDeclaration == null) {
                    // no args: automatic var 'it'
                    val l = scope.args.list
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
                try {
                    body.execute(context)
                } catch (e: ReturnException) {
                    if (e.label == null || e.label == label) e.result
                    else throw e
                }
            }.asReadonly
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
                        ?: throw ScriptError(t.pos, "spread element must have an expression")
                }

                else -> {
                    cc.previous()
                    parseExpressionLevel()?.let { expr ->
                        if (cc.current().type == Token.Type.ELLIPSIS) {
                            cc.next()
                            entries += ListEntry.Spread(expr)
                        } else {
                            entries += ListEntry.Element(expr)
                        }
                    } ?: throw ScriptError(t.pos, "invalid list literal: expecting expression")
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
     * Look ahead from current position (right after a leading '{') to find a top-level '->' before the matching '}'.
     * Returns true if such arrow is found, meaning the construct should be parsed as a lambda.
     * The scanner respects nested braces depth; only depth==1 arrows count.
     * The current cursor is restored on exit.
     */
    private fun hasTopLevelArrowBeforeRbrace(): Boolean {
        val start = cc.savePos()
        var depth = 1
        var found = false
        while (cc.hasNext()) {
            val t = cc.next()
            when (t.type) {
                Token.Type.LBRACE -> depth++
                Token.Type.RBRACE -> {
                    depth--
                    if (depth == 0) break
                }
                Token.Type.ARROW -> if (depth == 1) {
                    found = true
                    // Do not break; we still restore position below
                }
                else -> {}
            }
        }
        cc.restorePos(start)
        return found
    }

    /**
     * Attempt to parse a map literal starting at the position after '{'.
     * Returns null if the sequence does not look like a map literal (e.g., empty or first token is not STRING/ID/ELLIPSIS),
     * in which case caller should treat it as a lambda/block.
     * When it recognizes a map literal, it commits and throws on syntax errors.
     */
    private suspend fun parseMapLiteralOrNull(): ObjRef? {
        val startAfterLbrace = cc.savePos()
        // Peek first non-ws token to decide whether it's likely a map literal
        val first = cc.peekNextNonWhitespace()
        // Empty {} should be parsed as an empty map literal in expression context
        if (first.type == Token.Type.RBRACE) {
            // consume '}' and return empty map literal
            cc.next() // consume the RBRACE
            return MapLiteralRef(emptyList())
        }
        if (first.type !in listOf(Token.Type.STRING, Token.Type.ID, Token.Type.ELLIPSIS)) return null

        // Commit to map literal parsing
        cc.skipWsTokens()
        val entries = mutableListOf<MapLiteralEntry>()
        val usedKeys = mutableSetOf<String>()

        while (true) {
            // Skip whitespace/comments/newlines between entries
            val t0 = cc.nextNonWhitespace()
            when (t0.type) {
                Token.Type.RBRACE -> {
                    // end of map literal
                    return MapLiteralRef(entries)
                }
                Token.Type.COMMA -> {
                    // allow stray commas; continue
                    continue
                }
                Token.Type.ELLIPSIS -> {
                    // spread element: ... expression
                    val expr = parseExpressionLevel() ?: throw ScriptError(t0.pos, "invalid map spread: expecting expression")
                    entries += MapLiteralEntry.Spread(expr)
                    // Expect comma or '}' next; loop will handle
                }
                Token.Type.STRING, Token.Type.ID -> {
                    val isIdKey = t0.type == Token.Type.ID
                    val keyName = if (isIdKey) t0.value else t0.value
                    // After key we require ':'
                    cc.skipWsTokens()
                    val colon = cc.next()
                    if (colon.type != Token.Type.COLON) {
                        // Not a map literal after all; backtrack and signal null
                        cc.restorePos(startAfterLbrace)
                        return null
                    }
                    // Check for shorthand (only for id-keys): if next non-ws is ',' or '}'
                    cc.skipWsTokens()
                    val next = cc.next()
                    if ((next.type == Token.Type.COMMA || next.type == Token.Type.RBRACE)) {
                        if (!isIdKey) throw ScriptError(next.pos, "missing value after string-literal key '$keyName'")
                        // id: shorthand; value is the variable with the same name
                        // rewind one step if RBRACE so outer loop can handle it
                        if (next.type == Token.Type.RBRACE) cc.previous()
                        // Duplicate detection for literals only
                        if (!usedKeys.add(keyName)) throw ScriptError(t0.pos, "duplicate key '$keyName'")
                        entries += MapLiteralEntry.Named(keyName, LocalVarRef(keyName, t0.pos))
                        // If the token was COMMA, the loop continues; if it's RBRACE, next iteration will end
                    } else {
                        // There is a value expression: push back token and parse expression
                        cc.previous()
                        val valueRef = parseExpressionLevel() ?: throw ScriptError(colon.pos, "expecting map entry value")
                        if (!usedKeys.add(keyName)) throw ScriptError(t0.pos, "duplicate key '$keyName'")
                        entries += MapLiteralEntry.Named(keyName, valueRef)
                        // After value, allow optional comma; do not require it
                        cc.skipTokenOfType(Token.Type.COMMA, isOptional = true)
                        // The loop will continue and eventually see '}'
                    }
                }
                else -> {
                    // Not a map literal; backtrack and let caller treat as lambda
                    cc.restorePos(startAfterLbrace)
                    return null
                }
            }
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
                Token.Type.MULTILINE_COMMENT, Token.Type.SINGLE_LINE_COMMENT -> {}

                Token.Type.ID, Token.Type.ATLABEL -> {
                    var isTransient = false
                    if (t.type == Token.Type.ATLABEL) {
                        if (t.value == "Transient") {
                            isTransient = true
                            t = cc.next()
                        } else throw ScriptError(t.pos, "Unexpected label in argument list")
                    }

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

                    // type information (semantic + mini syntax)
                    val (typeInfo, miniType) = parseTypeDeclarationWithMini()

                    var defaultValue: Statement? = null
                    cc.ifNextIs(Token.Type.ASSIGN) {
                        defaultValue = parseExpression()
                    }
                    val isEllipsis = cc.skipTokenOfType(Token.Type.ELLIPSIS, isOptional = true)
                    result += ArgsDeclaration.Item(
                        t.value,
                        typeInfo,
                        miniType,
                        t.pos,
                        isEllipsis,
                        defaultValue,
                        access,
                        visibility,
                        isTransient
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

    @Suppress("unused")
    private fun parseTypeDeclaration(): TypeDecl {
        return parseTypeDeclarationWithMini().first
    }

    // Minimal helper to parse a type annotation and simultaneously build a MiniTypeRef.
    // Currently supports a simple identifier with optional nullable '?'.
    private fun parseTypeDeclarationWithMini(): Pair<TypeDecl, MiniTypeRef?> {
        // Only parse a type if a ':' follows; otherwise keep current behavior
        if (!cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) return Pair(TypeDecl.TypeAny, null)
        return parseTypeExpressionWithMini()
    }

    private fun parseTypeExpressionWithMini(): Pair<TypeDecl, MiniTypeRef> {
        // Parse a qualified base name: ID ('.' ID)*
        val segments = mutableListOf<MiniTypeName.Segment>()
        var first = true
        val typeStart = cc.currentPos()
        var lastEnd = typeStart
        while (true) {
            val idTok =
                if (first) cc.requireToken(Token.Type.ID, "type name or type expression required") else cc.requireToken(
                    Token.Type.ID,
                    "identifier expected after '.' in type"
                )
            first = false
            segments += MiniTypeName.Segment(idTok.value, MiniRange(idTok.pos, idTok.pos))
            lastEnd = cc.currentPos()
            val dotPos = cc.savePos()
            val t = cc.next()
            if (t.type == Token.Type.DOT) {
                // continue
                continue
            } else {
                cc.restorePos(dotPos)
                break
            }
        }

        // Helper to build MiniTypeRef (base or generic)
        fun buildBaseRef(rangeEnd: Pos, args: List<MiniTypeRef>?, nullable: Boolean): MiniTypeRef {
            val base = MiniTypeName(MiniRange(typeStart, rangeEnd), segments.toList(), nullable = false)
            return if (args == null || args.isEmpty()) base.copy(
                range = MiniRange(typeStart, rangeEnd),
                nullable = nullable
            )
            else MiniGenericType(MiniRange(typeStart, rangeEnd), base, args, nullable)
        }

        // Optional generic arguments: '<' Type (',' Type)* '>'
        var miniArgs: MutableList<MiniTypeRef>? = null
        var semArgs: MutableList<TypeDecl>? = null
        val afterBasePos = cc.savePos()
        if (cc.skipTokenOfType(Token.Type.LT, isOptional = true)) {
            miniArgs = mutableListOf()
            semArgs = mutableListOf()
            do {
                val (argSem, argMini) = parseTypeExpressionWithMini()
                miniArgs += argMini
                semArgs += argSem

                val sep = cc.next()
                if (sep.type == Token.Type.COMMA) {
                    // continue
                } else if (sep.type == Token.Type.GT) {
                    break
                } else if (sep.type == Token.Type.SHR) {
                    cc.pushPendingGT()
                    break
                } else {
                    sep.raiseSyntax("expected ',' or '>' in generic arguments")
                }
            } while (true)
            lastEnd = cc.currentPos()
        } else {
            cc.restorePos(afterBasePos)
        }

        // Nullable suffix after base or generic
        val isNullable = if (cc.skipTokenOfType(Token.Type.QUESTION, isOptional = true)) {
            true
        } else if (cc.skipTokenOfType(Token.Type.IFNULLASSIGN, isOptional = true)) {
            cc.pushPendingAssign()
            true
        } else false
        val endPos = cc.currentPos()

        val miniRef = buildBaseRef(if (miniArgs != null) endPos else lastEnd, miniArgs, isNullable)
        // Semantic: keep simple for now, just use qualified base name with nullable flag
        val qualified = segments.joinToString(".") { it.name }
        val sem = if (semArgs != null) TypeDecl.Generic(qualified, semArgs, isNullable)
        else TypeDecl.Simple(qualified, isNullable)
        return Pair(sem, miniRef)
    }

    /**
     * Parse arguments list during the call and detect last block argument
     * _following the parenthesis_ call: `(1,2) { ... }`
     */
    private suspend fun parseArgs(): Pair<List<ParsedArgument>, Boolean> {

        val args = mutableListOf<ParsedArgument>()
        suspend fun tryParseNamedArg(): ParsedArgument? {
            val save = cc.savePos()
            val t1 = cc.next()
            if (t1.type == Token.Type.ID) {
                val t2 = cc.next()
                if (t2.type == Token.Type.COLON) {
                    // name: expr
                    val name = t1.value
                    // Check for shorthand: name: (comma or rparen)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.COMMA || next.type == Token.Type.RPAREN) {
                        val localVar = LocalVarRef(name, t1.pos)
                        return ParsedArgument(statement(t1.pos) { localVar.evalValue(it) }, t1.pos, isSplat = false, name = name)
                    }
                    val rhs = parseExpression() ?: t2.raiseSyntax("expected expression after named argument '${name}:'")
                    return ParsedArgument(rhs, t1.pos, isSplat = false, name = name)
                }
            }
            cc.restorePos(save)
            return null
        }
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.NEWLINE,
                Token.Type.RPAREN, Token.Type.COMMA -> {
                }

                Token.Type.ELLIPSIS -> {
                    parseExpression()?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    val named = tryParseNamedArg()
                    if (named != null) {
                        args += named
                    } else {
                        parseExpression()?.let { args += ParsedArgument(it, t.pos) }
                            ?: throw ScriptError(t.pos, "Expecting arguments list")
                        // In call-site arguments, ':' is reserved for named args. Do not parse type declarations here.
                    }
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

    /**
     * Parse arguments inside parentheses without consuming any optional trailing block after the RPAREN.
     * Useful in contexts where a following '{' has different meaning (e.g., class bodies after base lists).
     */
    private suspend fun parseArgsNoTailBlock(): List<ParsedArgument> {
        val args = mutableListOf<ParsedArgument>()
        suspend fun tryParseNamedArg(): ParsedArgument? {
            val save = cc.savePos()
            val t1 = cc.next()
            if (t1.type == Token.Type.ID) {
                val t2 = cc.next()
                if (t2.type == Token.Type.COLON) {
                    val name = t1.value
                    // Check for shorthand: name: (comma or rparen)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.COMMA || next.type == Token.Type.RPAREN) {
                        val localVar = LocalVarRef(name, t1.pos)
                        return ParsedArgument(statement(t1.pos) { localVar.evalValue(it) }, t1.pos, isSplat = false, name = name)
                    }
                    val rhs = parseExpression() ?: t2.raiseSyntax("expected expression after named argument '${name}:'")
                    return ParsedArgument(rhs, t1.pos, isSplat = false, name = name)
                }
            }
            cc.restorePos(save)
            return null
        }
        do {
            val t = cc.next()
            when (t.type) {
                Token.Type.NEWLINE,
                Token.Type.RPAREN, Token.Type.COMMA -> {
                }

                Token.Type.ELLIPSIS -> {
                    parseExpression()?.let { args += ParsedArgument(it, t.pos, isSplat = true) }
                        ?: throw ScriptError(t.pos, "Expecting arguments list")
                }

                else -> {
                    cc.previous()
                    val named = tryParseNamedArg()
                    if (named != null) {
                        args += named
                    } else {
                        parseExpression()?.let { args += ParsedArgument(it, t.pos) }
                            ?: throw ScriptError(t.pos, "Expecting arguments list")
                        // Do not parse type declarations in call args
                    }
                }
            }
        } while (t.type != Token.Type.RPAREN)
        // Do NOT peek for a trailing block; leave it to the outer parser
        return args
    }


    private suspend fun parseFunctionCall(
        left: ObjRef,
        blockArgument: Boolean,
        isOptional: Boolean
    ): ObjRef {
        var detectedBlockArgument = blockArgument
        val args = if (blockArgument) {
            // Leading '{' has already been consumed by the caller token branch.
            // Parse only the lambda expression as the last argument and DO NOT
            // allow any subsequent selectors (like ".last()") to be absorbed
            // into the lambda body. This ensures expected order:
            //   foo { ... }.bar()  ==  (foo { ... }).bar()
            val callableAccessor = parseLambdaExpression()
            val argStmt = statement { callableAccessor.get(this).value }
            listOf(ParsedArgument(argStmt, cc.currentPos()))
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
                // Special case: qualified this -> this@Type
                if (t.value == "this") {
                    val pos = cc.savePos()
                    val next = cc.next()
                    if (next.pos.line == t.pos.line && next.type == Token.Type.ATLABEL) {
                        QualifiedThisRef(next.value, t.pos)
                    } else {
                        cc.restorePos(pos)
                        // plain this
                        LocalVarRef("this", t.pos)
                    }
                } else when (t.value) {
                    "void" -> ConstRef(ObjVoid.asReadonly)
                    "null" -> ConstRef(ObjNull.asReadonly)
                    "true" -> ConstRef(ObjTrue.asReadonly)
                    "false" -> ConstRef(ObjFalse.asReadonly)
                    else -> if (PerfFlags.EMIT_FAST_LOCAL_REFS && (currentLocalNames?.contains(t.value) == true))
                        FastLocalVarRef(t.value, t.pos)
                    else LocalVarRef(t.value, t.pos)
                }
            }

            Token.Type.OBJECT -> StatementRef(parseObjectDeclaration())

            else -> null
        }
    }

    private fun parseNumberOrNull(isPlus: Boolean): Obj? {
        val pos = cc.savePos()
        val t = cc.next()
        return when (t.type) {
            Token.Type.INT, Token.Type.HEX -> {
                val n = t.value.replace("_", "").toLong(if (t.type == Token.Type.HEX) 16 else 10)
                if (isPlus) ObjInt.of(n) else ObjInt.of(-n)
            }

            Token.Type.REAL -> {
                val d = t.value.toDouble()
                if (isPlus) ObjReal.of(d) else ObjReal.of(-d)
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

    private suspend fun parseDeclarationWithModifiers(firstId: Token): Statement {
        val modifiers = mutableSetOf<String>()
        var currentToken = firstId

        while (true) {
            when (currentToken.value) {
                "private", "protected", "static", "abstract", "closed", "override", "extern", "open" -> {
                    modifiers.add(currentToken.value)
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.ID || next.type == Token.Type.OBJECT) {
                        currentToken = nextNonWhitespace()
                    } else {
                        break
                    }
                }

                else -> break
            }
        }

        val visibility = when {
            modifiers.contains("private") -> Visibility.Private
            modifiers.contains("protected") -> Visibility.Protected
            else -> Visibility.Public
        }
        val isStatic = modifiers.contains("static")
        val isAbstract = modifiers.contains("abstract")
        val isClosed = modifiers.contains("closed")
        val isOverride = modifiers.contains("override")
        val isExtern = modifiers.contains("extern")

        if (isStatic && (isAbstract || isOverride || isClosed))
            throw ScriptError(currentToken.pos, "static members cannot be abstract, closed or override")

        if (visibility == Visibility.Private && isAbstract)
            throw ScriptError(currentToken.pos, "abstract members cannot be private")

        pendingDeclStart = firstId.pos
        // pendingDeclDoc might be already set by an annotation
        if (pendingDeclDoc == null)
            pendingDeclDoc = consumePendingDoc()

        val isMember = (codeContexts.lastOrNull() is CodeContext.ClassBody)

        if (!isMember && isClosed)
            throw ScriptError(currentToken.pos, "modifier closed is only allowed for class members")

        if (!isMember && isOverride && currentToken.value != "fun" && currentToken.value != "fn")
            throw ScriptError(currentToken.pos, "modifier override outside class is only allowed for extension functions")

        if (!isMember && isAbstract && currentToken.value != "class")
            throw ScriptError(currentToken.pos, "modifier abstract at top level is only allowed for classes")

        return when (currentToken.value) {
            "val" -> parseVarDeclaration(false, visibility, isAbstract, isClosed, isOverride, isStatic, isExtern)
            "var" -> parseVarDeclaration(true, visibility, isAbstract, isClosed, isOverride, isStatic, isExtern)
            "fun", "fn" -> parseFunctionDeclaration(visibility, isAbstract, isClosed, isOverride, isExtern, isStatic)
            "class" -> {
                if (isStatic || isClosed || isOverride)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for class: ${modifiers.joinToString(" ")}"
                    )
                parseClassDeclaration(isAbstract, isExtern)
            }

            "object" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for object: ${modifiers.joinToString(" ")}"
                    )
                parseObjectDeclaration(isExtern)
            }

            "interface" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for interface: ${modifiers.joinToString(" ")}"
                    )
                // interface is synonym for abstract class
                parseClassDeclaration(isAbstract = true, isExtern = isExtern)
            }

            "enum" -> {
                if (isStatic || isClosed || isOverride || isAbstract)
                    throw ScriptError(
                        currentToken.pos,
                        "unsupported modifiers for enum: ${modifiers.joinToString(" ")}"
                    )
                parseEnumDeclaration(isExtern)
            }

            else -> throw ScriptError(
                currentToken.pos,
                "expected declaration after modifiers, found ${currentToken.value}"
            )
        }
    }

    /**
     * Parse keyword-starting statement.
     * @return parsed statement or null if, for example. [id] is not among keywords
     */
    private suspend fun parseKeywordStatement(id: Token): Statement? = when (id.value) {
        "abstract", "closed", "override", "extern", "private", "protected", "static", "open" -> {
            parseDeclarationWithModifiers(id)
        }

        "interface" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseClassDeclaration(isAbstract = true)
        }

        "val" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseVarDeclaration(false, Visibility.Public)
        }

        "var" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseVarDeclaration(true, Visibility.Public)
        }
        // Ensure function declarations are recognized in all contexts (including class bodies)
        "fun" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseFunctionDeclaration(isExtern = false, isStatic = false)
        }

        "fn" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseFunctionDeclaration(isExtern = false, isStatic = false)
        }
        // Visibility modifiers for declarations: private/protected val/var/fun/fn
        "while" -> parseWhileStatement()
        "do" -> parseDoWhileStatement()
        "for" -> parseForStatement()
        "return" -> parseReturnStatement(id.pos)
        "break" -> parseBreakStatement(id.pos)
        "continue" -> parseContinueStatement(id.pos)
        "if" -> parseIfStatement()
        "class" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseClassDeclaration()
        }

        "object" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseObjectDeclaration()
        }

        "init" -> {
            if (codeContexts.lastOrNull() is CodeContext.ClassBody && cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                miniSink?.onEnterFunction(null)
                val block = parseBlock()
                miniSink?.onExitFunction(cc.currentPos())
                lastParsedBlockRange?.let { range ->
                    miniSink?.onInitDecl(MiniInitDecl(MiniRange(id.pos, range.end), id.pos))
                }
                val initStmt = statement(id.pos) { scp ->
                    val cls = scp.thisObj.objClass
                    val saved = scp.currentClassCtx
                    scp.currentClassCtx = cls
                    try {
                        block.execute(scp)
                    } finally {
                        scp.currentClassCtx = saved
                    }
                    ObjVoid
                }
                statement {
                    currentClassCtx?.instanceInitializers?.add(initStmt)
                    ObjVoid
                }
            } else null
        }

        "enum" -> {
            pendingDeclStart = id.pos
            pendingDeclDoc = consumePendingDoc()
            parseEnumDeclaration()
        }

        "try" -> parseTryStatement()
        "throw" -> parseThrowStatement(id.pos)
        "when" -> parseWhenStatement()
        else -> {
            // triples
            cc.previous()
            val isExtern = cc.skipId("extern")
            when {
                cc.matchQualifiers("fun", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc()
                    parseFunctionDeclaration(Visibility.Private, isExtern)
                }

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
                cc.matchQualifiers("fun", "open") -> parseFunctionDeclaration(isExtern = isExtern)
                cc.matchQualifiers("fn", "open") -> parseFunctionDeclaration(isExtern = isExtern)

                cc.matchQualifiers("fun") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc =
                        consumePendingDoc(); parseFunctionDeclaration(isExtern = isExtern)
                }

                cc.matchQualifiers("fn") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc =
                        consumePendingDoc(); parseFunctionDeclaration(isExtern = isExtern)
                }

                cc.matchQualifiers("val", "private", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("val", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Public,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("val", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private
                    )
                }

                cc.matchQualifiers("var", "static") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Public,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("var", "static", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private,
                        isStatic = true
                    )
                }

                cc.matchQualifiers("var", "private") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private
                    )
                }

                cc.matchQualifiers("val", "open") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        false,
                        Visibility.Private,
                        true
                    )
                }

                cc.matchQualifiers("var", "open") -> {
                    pendingDeclStart = id.pos; pendingDeclDoc = consumePendingDoc(); parseVarDeclaration(
                        true,
                        Visibility.Private,
                        true
                    )
                }

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

    private suspend fun parseThrowStatement(start: Pos): Statement {
        val throwStatement = parseStatement() ?: throw ScriptError(cc.currentPos(), "throw object expected")
        // Important: bind the created statement to the position of the `throw` keyword so that
        // any raised error reports the correct source location.
        return statement(start) { sc ->
            var errorObject = throwStatement.execute(sc)
            // Rebind error scope to the throw-site position so ScriptError.pos is accurate
            val throwScope = sc.createChildScope(pos = start)
            if (errorObject is ObjString) {
                errorObject = ObjException(throwScope, errorObject.value).apply { getStackTrace() }
            }
            if (!errorObject.isInstanceOf(ObjException.Root)) {
                throwScope.raiseError("this is not an exception object: $errorObject")
            }
            if (errorObject is ObjException) {
                errorObject = ObjException(
                    errorObject.exceptionClass,
                    throwScope,
                    errorObject.message,
                    errorObject.extraData,
                    errorObject.useStackTrace
                ).apply { getStackTrace() }
                throwScope.raiseError(errorObject)
            } else {
                val msg = errorObject.invokeInstanceMethod(sc, "message").toString(sc).value
                throwScope.raiseError(errorObject, start, msg)
            }
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
            } catch (e: ReturnException) {
                throw e
            } catch (e: LoopBreakContinueException) {
                throw e
            } catch (e: Exception) {
                // convert to appropriate exception
                val caughtObj = when (e) {
                    is ExecutionError -> e.errorObject
                    else -> ObjUnknownException(this, e.message ?: e.toString())
                }
                // let's see if we should catch it:
                var isCaught = false
                for (cdata in catches) {
                    var match: Obj? = null
                    for (exceptionClassName in cdata.classNames) {
                        val exObj = this[exceptionClassName]?.value as? ObjClass
                            ?: raiseSymbolNotFound("error class does not exist or is not a class: $exceptionClassName")
                        if (caughtObj.isInstanceOf(exObj)) {
                            match = caughtObj
                            break
                        }
                    }
                    if (match != null) {
                        val catchContext = this.createChildScope(pos = cdata.catchVar.pos)
                        catchContext.addItem(cdata.catchVar.value, false, caughtObj)
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

    private fun parseEnumDeclaration(isExtern: Boolean = false): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        val startPos = pendingDeclStart ?: nameToken.pos
        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null
        // so far only simplest enums:
        val names = mutableListOf<String>()
        val positions = mutableListOf<Pos>()
        // skip '{'
        cc.skipTokenOfType(Token.Type.LBRACE)

        if (cc.peekNextNonWhitespace().type != Token.Type.RBRACE) {
            do {
                val t = cc.nextNonWhitespace()
                when (t.type) {
                    Token.Type.ID -> {
                        names += t.value
                        positions += t.pos
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
        } else {
            cc.nextNonWhitespace()
        }

        miniSink?.onEnumDecl(
            MiniEnumDecl(
                range = MiniRange(startPos, cc.currentPos()),
                name = nameToken.value,
                entries = names,
                doc = doc,
                nameStart = nameToken.pos,
                isExtern = isExtern,
                entryPositions = positions
            )
        )

        return statement {
            ObjEnumClass.createSimpleEnum(nameToken.value, names).also {
                addItem(nameToken.value, false, it, recordType = ObjRecord.Type.Enum)
            }
        }
    }

    private suspend fun parseObjectDeclaration(isExtern: Boolean = false): Statement {
        val next = cc.peekNextNonWhitespace()
        val nameToken = if (next.type == Token.Type.ID) cc.requireToken(Token.Type.ID) else null

        val startPos = pendingDeclStart ?: nameToken?.pos ?: cc.current().pos
        val className = nameToken?.value ?: generateAnonName(startPos)

        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null

        // Optional base list: ":" Base ("," Base)* where Base := ID ( "(" args? ")" )?
        data class BaseSpec(val name: String, val args: List<ParsedArgument>?)

        val baseSpecs = mutableListOf<BaseSpec>()
        if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
            do {
                val baseId = cc.requireToken(Token.Type.ID, "base class name expected")
                var argsList: List<ParsedArgument>? = null
                if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                    argsList = parseArgsNoTailBlock()
                }
                baseSpecs += BaseSpec(baseId.value, argsList)
            } while (cc.skipTokenOfType(Token.Type.COMMA, isOptional = true))
        }

        cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)

        pushInitScope()

        // Robust body detection
        var classBodyRange: MiniRange? = null
        val bodyInit: Statement? = inCodeContext(CodeContext.ClassBody(className, isExtern = isExtern)) {
            val saved = cc.savePos()
            val nextBody = cc.nextNonWhitespace()
            if (nextBody.type == Token.Type.LBRACE) {
                // Emit MiniClassDecl before body parsing to track members via enter/exit
                run {
                    val node = MiniClassDecl(
                        range = MiniRange(startPos, cc.currentPos()),
                        name = className,
                        bases = baseSpecs.map { it.name },
                        bodyRange = null,
                        doc = doc,
                        nameStart = nameToken?.pos ?: startPos,
                        isObject = true,
                        isExtern = isExtern
                    )
                    miniSink?.onEnterClass(node)
                }
                val bodyStart = nextBody.pos
                val st = withLocalNames(emptySet()) {
                    parseScript()
                }
                val rbTok = cc.next()
                if (rbTok.type != Token.Type.RBRACE) throw ScriptError(rbTok.pos, "unbalanced braces in object body")
                classBodyRange = MiniRange(bodyStart, rbTok.pos)
                miniSink?.onExitClass(rbTok.pos)
                st
            } else {
                // No body, but still emit the class
                run {
                    val node = MiniClassDecl(
                        range = MiniRange(startPos, cc.currentPos()),
                        name = className,
                        bases = baseSpecs.map { it.name },
                        bodyRange = null,
                        doc = doc,
                        nameStart = nameToken?.pos ?: startPos,
                        isObject = true,
                        isExtern = isExtern
                    )
                    miniSink?.onClassDecl(node)
                }
                cc.restorePos(saved)
                null
            }
        }

        val initScope = popInitScope()

        return statement(startPos) { context ->
            val parentClasses = baseSpecs.map { baseSpec ->
                val rec = context[baseSpec.name] ?: throw ScriptError(startPos, "unknown base class: ${baseSpec.name}")
                (rec.value as? ObjClass) ?: throw ScriptError(startPos, "${baseSpec.name} is not a class")
            }

            val newClass = ObjInstanceClass(className, *parentClasses.toTypedArray())
            newClass.isAnonymous = nameToken == null
            newClass.constructorMeta = ArgsDeclaration(emptyList(), Token.Type.RPAREN)
            for (i in parentClasses.indices) {
                val argsList = baseSpecs[i].args
                // In object, we evaluate parent args once at creation time
                if (argsList != null) newClass.directParentArgs[parentClasses[i]] = argsList
            }

            val classScope = context.createChildScope(newThisObj = newClass)
            classScope.currentClassCtx = newClass
            newClass.classScope = classScope
            classScope.addConst("object", newClass)

            bodyInit?.execute(classScope)

            // Create instance (singleton)
            val instance = newClass.callOn(context.createChildScope(Arguments.EMPTY))
            if (nameToken != null)
                context.addItem(className, false, instance)
            instance
        }
    }

    private suspend fun parseClassDeclaration(isAbstract: Boolean = false, isExtern: Boolean = false): Statement {
        val nameToken = cc.requireToken(Token.Type.ID)
        val startPos = pendingDeclStart ?: nameToken.pos
        val doc = pendingDeclDoc ?: consumePendingDoc()
        pendingDeclDoc = null
        pendingDeclStart = null
        return inCodeContext(CodeContext.ClassBody(nameToken.value, isExtern = isExtern)) {
            val constructorArgsDeclaration =
                if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true))
                    parseArgsDeclaration(isClassDeclaration = true)
                else ArgsDeclaration(emptyList(), Token.Type.RPAREN)

            if (constructorArgsDeclaration != null && constructorArgsDeclaration.endTokenType != Token.Type.RPAREN)
                throw ScriptError(
                    nameToken.pos,
                    "Bad class declaration: expected ')' at the end of the primary constructor"
                )

            // Optional base list: ":" Base ("," Base)* where Base := ID ( "(" args? ")" )?
            data class BaseSpec(val name: String, val args: List<ParsedArgument>?)

            val baseSpecs = mutableListOf<BaseSpec>()
            if (cc.skipTokenOfType(Token.Type.COLON, isOptional = true)) {
                do {
                    val baseId = cc.requireToken(Token.Type.ID, "base class name expected")
                    var argsList: List<ParsedArgument>? = null
                    // Optional constructor args of the base — parse and ignore for now (MVP), just to consume tokens
                    if (cc.skipTokenOfType(Token.Type.LPAREN, isOptional = true)) {
                        // Parse args without consuming any following block so that a class body can follow safely
                        argsList = parseArgsNoTailBlock()
                    }
                    baseSpecs += BaseSpec(baseId.value, argsList)
                } while (cc.skipTokenOfType(Token.Type.COMMA, isOptional = true))
            }

            cc.skipTokenOfType(Token.Type.NEWLINE, isOptional = true)

            pushInitScope()

            // Robust body detection: peek next non-whitespace token; if it's '{', consume and parse the body
            var classBodyRange: MiniRange? = null
            val bodyInit: Statement? = run {
                val saved = cc.savePos()
                val next = cc.nextNonWhitespace()
                
                val ctorFields = mutableListOf<MiniCtorField>()
                constructorArgsDeclaration?.let { ad ->
                    for (p in ad.params) {
                        val at = p.accessType
                        val mutable = at == AccessType.Var
                        ctorFields += MiniCtorField(
                            name = p.name,
                            mutable = mutable,
                            type = p.miniType,
                            nameStart = p.pos
                        )
                    }
                }

                if (next.type == Token.Type.LBRACE) {
                    // Emit MiniClassDecl before body parsing to track members via enter/exit
                    run {
                        val node = MiniClassDecl(
                            range = MiniRange(startPos, cc.currentPos()),
                            name = nameToken.value,
                            bases = baseSpecs.map { it.name },
                            bodyRange = null,
                            ctorFields = ctorFields,
                            doc = doc,
                            nameStart = nameToken.pos,
                            isExtern = isExtern
                        )
                        miniSink?.onEnterClass(node)
                    }
                    // parse body
                    val bodyStart = next.pos
                    val st = withLocalNames(constructorArgsDeclaration?.params?.map { it.name }?.toSet() ?: emptySet()) {
                        parseScript()
                    }
                    val rbTok = cc.next()
                    if (rbTok.type != Token.Type.RBRACE) throw ScriptError(rbTok.pos, "unbalanced braces in class body")
                    classBodyRange = MiniRange(bodyStart, rbTok.pos)
                    miniSink?.onExitClass(rbTok.pos)
                    st
                } else {
                    // No body, but still emit the class
                    run {
                        val node = MiniClassDecl(
                            range = MiniRange(startPos, cc.currentPos()),
                            name = nameToken.value,
                            bases = baseSpecs.map { it.name },
                            bodyRange = null,
                            ctorFields = ctorFields,
                            doc = doc,
                            nameStart = nameToken.pos,
                            isExtern = isExtern
                        )
                        miniSink?.onClassDecl(node)
                    }
                    // restore if no body starts here
                    cc.restorePos(saved)
                    null
                }
            }

            val initScope = popInitScope()

            // create class
            val className = nameToken.value

//        @Suppress("UNUSED_VARIABLE") val defaultAccess = if (isStruct) AccessType.Variable else AccessType.Initialization
//        @Suppress("UNUSED_VARIABLE") val defaultVisibility = Visibility.Public

            // create instance constructor
            // create custom objClass with all fields and instance constructor

            val constructorCode = statement {
                // constructor code is registered with class instance and is called over
                // new `thisObj` already set by class to ObjInstance.instanceContext
                val instance = thisObj as ObjInstance
                // Constructor parameters have been assigned to instance scope by ObjClass.callOn before
                // invoking parent/child constructors.
                // IMPORTANT: do not execute class body here; class body was executed once in the class scope
                // to register methods and prepare initializers. Instance constructor should be empty unless
                // we later add explicit constructor body syntax.
                instance
            }
            statement {
                // the main statement should create custom ObjClass instance with field
                // accessors, constructor registration, etc.
                // Resolve parent classes by name at execution time
                val parentClasses = baseSpecs.map { baseSpec ->
                    val rec =
                        this[baseSpec.name] ?: throw ScriptError(nameToken.pos, "unknown base class: ${baseSpec.name}")
                    (rec.value as? ObjClass) ?: throw ScriptError(nameToken.pos, "${baseSpec.name} is not a class")
                }

                val newClass = ObjInstanceClass(className, *parentClasses.toTypedArray()).also {
                    it.isAbstract = isAbstract
                    it.instanceConstructor = constructorCode
                    it.constructorMeta = constructorArgsDeclaration
                    // Attach per-parent constructor args (thunks) if provided
                    for (i in parentClasses.indices) {
                        val argsList = baseSpecs[i].args
                        if (argsList != null) it.directParentArgs[parentClasses[i]] = argsList
                    }
                    // Register constructor fields in the class members
                    constructorArgsDeclaration?.params?.forEach { p ->
                        if (p.accessType != null) {
                            it.createField(
                                p.name, ObjNull,
                                isMutable = p.accessType == AccessType.Var,
                                visibility = p.visibility ?: Visibility.Public,
                                declaringClass = it,
                                // Constructor fields are not currently supporting override/closed in parser
                                // but we should pass Pos.builtIn to skip validation for now if needed,
                                // or p.pos to allow it.
                                pos = Pos.builtIn,
                                isTransient = p.isTransient,
                                type = ObjRecord.Type.ConstructorField
                            )
                        }
                    }
                }

                addItem(className, false, newClass)
                // Prepare class scope for class-scope members (static) and future registrations
                val classScope = createChildScope(newThisObj = newClass)
                // Set lexical class context for visibility tagging inside class body
                classScope.currentClassCtx = newClass
                newClass.classScope = classScope
                // Execute class body once in class scope to register instance methods and prepare instance field initializers
                bodyInit?.execute(classScope)
                if (initScope.isNotEmpty()) {
                    for (s in initScope)
                        s.execute(classScope)
                }
                newClass.checkAbstractSatisfaction(nameToken.pos)
                // Debug summary: list registered instance methods and class-scope functions for this class
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
            if (t.type == Token.Type.LABEL || t.type == Token.Type.ATLABEL) {
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
            // We must parse an expression here. Using parseStatement() would treat a leading '{'
            // as a block, breaking inline map literals like: for (i in {foo: "bar"}) { ... }
            // So we parse an expression explicitly and wrap it into a StatementRef.
            val exprAfterIn = parseExpression() ?: throw ScriptError(start, "Bad for statement: expected expression")
            val source: Statement = exprAfterIn
            ensureRparen()

            // Expose the loop variable name to the parser so identifiers inside the loop body
            // can be emitted as FastLocalVarRef when enabled.
            val namesForLoop = (currentLocalNames?.toSet() ?: emptySet()) + tVar.value
            val (canBreak, body, elseStatement) = withLocalNames(namesForLoop) {
                val loopParsed = cc.parseLoop {
                    if (cc.current().type == Token.Type.LBRACE) parseBlock()
                    else parseStatement() ?: throw ScriptError(start, "Bad for statement: expected loop body")
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
                    val size = runCatching { sourceObj.readField(forContext, "size").value.toInt() }
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
                        var current = runCatching { sourceObj.getAt(forContext, ObjInt.of(0)) }
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
                            current = sourceObj.getAt(forContext, ObjInt.of(index.toLong()))
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
        if (catchBreak) {
            for (i in start..<end) {
                loopVar.value = ObjInt.of(i)
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
                loopVar.value = ObjInt.of(i)
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
        var result: Obj = ObjVoid
        var breakCaught = false
        sourceObj.enumerate(forScope) { item ->
            loopVar.value = item
            if (catchBreak) {
                try {
                    result = body.execute(forScope)
                    true
                } catch (lbe: LoopBreakContinueException) {
                    if (lbe.label == label || lbe.label == null) {
                        if (lbe.doContinue) true
                        else {
                            result = lbe.result
                            breakCaught = true
                            false
                        }
                    } else
                        throw lbe
                }
            } else {
                result = body.execute(forScope)
                true
            }
        }
        return if (!breakCaught && elseStatement != null) {
            elseStatement.execute(forScope)
        } else result
    }

    @Suppress("UNUSED_VARIABLE")
    private suspend fun parseDoWhileStatement(): Statement {
        val label = getLabel()?.also { cc.labels += it }
        val (canBreak, body) = cc.parseLoop {
            parseStatement() ?: throw ScriptError(cc.currentPos(), "Bad do-while statement: expected body statement")
        }
        label?.also { cc.labels -= it }

        cc.skipWsTokens()
        val tWhile = cc.next()
        if (tWhile.type != Token.Type.ID || tWhile.value != "while")
            throw ScriptError(tWhile.pos, "Expected 'while' after do body")

        ensureLparen()
        val condition = parseExpression() ?: throw ScriptError(cc.currentPos(), "Expected condition after 'while'")
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
            while (true) {
                val doScope = it.createChildScope().apply { skipScopeCreation = true }
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
                        throw e
                    }
                }
                if (!condition.execute(doScope).toBool()) {
                    break
                }
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

        val (canBreak, body) = cc.parseLoop {
            if (cc.current().type == Token.Type.LBRACE) parseBlock()
            else parseStatement() ?: throw ScriptError(start, "Bad while statement: expected statement")
        }
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
                val loopScope = it.createChildScope()
                if (canBreak) {
                    try {
                        result = body.execute(loopScope)
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
                } else
                    result = body.execute(loopScope)
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
                    t.type != Token.Type.NEWLINE &&
                    t.type != Token.Type.RBRACE &&
                    t.type != Token.Type.RPAREN &&
                    t.type != Token.Type.RBRACKET &&
                    t.type != Token.Type.COMMA &&
                    t.type != Token.Type.EOF)
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

    private suspend fun parseReturnStatement(start: Pos): Statement {
        var t = cc.next()

        val label = if (t.pos.line != start.line || t.type != Token.Type.ATLABEL) {
            cc.previous()
            null
        } else {
            t.value
        }

        // expression?
        t = cc.next()
        cc.previous()
        val resultExpr = if (t.pos.line == start.line && (!t.isComment &&
                    t.type != Token.Type.SEMICOLON &&
                    t.type != Token.Type.NEWLINE &&
                    t.type != Token.Type.RBRACE &&
                    t.type != Token.Type.RPAREN &&
                    t.type != Token.Type.RBRACKET &&
                    t.type != Token.Type.COMMA &&
                    t.type != Token.Type.EOF)
        ) {
            // we have something on this line, could be expression
            parseExpression()
        } else null

        return statement(start) {
            val returnValue = resultExpr?.execute(it) ?: ObjVoid
            throw ReturnException(returnValue, label)
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
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isExtern: Boolean = false,
        isStatic: Boolean = false,
        isTransient: Boolean = isTransientFlag
    ): Statement {
        isTransientFlag = false
        val actualExtern = isExtern || (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.isExtern == true
        var t = cc.next()
        val start = t.pos
        var extTypeName: String? = null
        var name = if (t.type != Token.Type.ID)
            throw ScriptError(t.pos, "Expected identifier after 'fun'")
        else t.value
        var nameStartPos: Pos = t.pos
        var receiverMini: MiniTypeRef? = null

        val annotation = lastAnnotation
        val parentContext = codeContexts.last()

        // Is extension?
        if (cc.peekNextNonWhitespace().type == Token.Type.DOT) {
            cc.nextNonWhitespace() // consume DOT
            extTypeName = name
            val receiverEnd = Pos(start.source, start.line, start.column + name.length)
            receiverMini = MiniTypeName(
                range = MiniRange(start, receiverEnd),
                segments = listOf(MiniTypeName.Segment(name, MiniRange(start, receiverEnd))),
                nullable = false
            )
            t = cc.next()
            if (t.type != Token.Type.ID)
                throw ScriptError(t.pos, "illegal extension format: expected function name")
            name = t.value
            nameStartPos = t.pos
        }

        val argsDeclaration: ArgsDeclaration =
            if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                cc.nextNonWhitespace() // consume (
                parseArgsDeclaration() ?: ArgsDeclaration(emptyList(), Token.Type.RPAREN)
            } else ArgsDeclaration(emptyList(), Token.Type.RPAREN)

        // Optional return type
        val returnTypeMini: MiniTypeRef? = if (cc.peekNextNonWhitespace().type == Token.Type.COLON) {
            parseTypeDeclarationWithMini().second
        } else null

        var isDelegated = false
        var delegateExpression: Statement? = null
        if (cc.peekNextNonWhitespace().type == Token.Type.BY) {
            cc.nextNonWhitespace() // consume by
            isDelegated = true
            delegateExpression = parseExpression() ?: throw ScriptError(cc.current().pos, "Expected delegate expression")
        }

        if (!isDelegated && argsDeclaration.endTokenType != Token.Type.RPAREN)
            throw ScriptError(
                t.pos,
                "Bad function definition: expected valid argument declaration or () after 'fn ${name}'"
            )

        // Capture doc locally to reuse even if we need to emit later
        val declDocLocal = pendingDeclDoc
        val outerLabel = lastLabel

        val node = run {
            val params = argsDeclaration.params.map { p ->
                MiniParam(
                    name = p.name,
                    type = p.miniType,
                    nameStart = p.pos
                )
            }
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val node = MiniFunDecl(
                range = declRange,
                name = name,
                params = params,
                returnType = returnTypeMini,
                body = null,
                doc = declDocLocal,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onFunDecl(node)
            pendingDeclDoc = null
            node
        }

        miniSink?.onEnterFunction(node)
        return inCodeContext(CodeContext.Function(name)) {
            cc.labels.add(name)
            outerLabel?.let { cc.labels.add(it) }

            val paramNames: Set<String> = argsDeclaration.params.map { it.name }.toSet()

            // Parse function body while tracking declared locals to compute precise capacity hints
            currentLocalDeclCount
            localDeclCountStack.add(0)
            val fnStatements = if (actualExtern)
                statement { raiseError("extern function not provided: $name") }
            else if (isAbstract || isDelegated) {
                null
            } else
                withLocalNames(paramNames) {
                    val next = cc.peekNextNonWhitespace()
                    if (next.type == Token.Type.ASSIGN) {
                        cc.nextNonWhitespace() // consume '='
                        if (cc.peekNextNonWhitespace().value == "return")
                            throw ScriptError(cc.currentPos(), "return is not allowed in shorthand function")
                        val expr = parseExpression() ?: throw ScriptError(cc.currentPos(), "Expected function body expression")
                        // Shorthand function returns the expression value
                        statement(expr.pos) { scope ->
                            expr.execute(scope)
                        }
                    } else {
                        parseBlock()
                    }
                }
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
                try {
                    fnStatements?.execute(context) ?: ObjVoid
                } catch (e: ReturnException) {
                    if (e.label == null || e.label == name || e.label == outerLabel) e.result
                    else throw e
                }
            }
            cc.labels.remove(name)
            outerLabel?.let { cc.labels.remove(it) }
//            parentContext
            val fnCreateStatement = statement(start) { context ->
                if (isDelegated) {
                    val accessType = context.resolveQualifiedIdentifier("DelegateAccess.Callable")
                    val initValue = delegateExpression!!.execute(context)
                    val finalDelegate = try {
                        initValue.invokeInstanceMethod(context, "bind", Arguments(ObjString(name), accessType, context.thisObj))
                    } catch (e: Exception) {
                        initValue
                    }

                    if (extTypeName != null) {
                        val type = context[extTypeName]?.value ?: context.raiseSymbolNotFound("class $extTypeName not found")
                        if (type !is ObjClass) context.raiseClassCastError("$extTypeName is not the class instance")
                        context.addExtension(type, name, ObjRecord(ObjUnset, isMutable = false, visibility = visibility, declaringClass = null, type = ObjRecord.Type.Delegated).apply {
                            delegate = finalDelegate
                        })
                        return@statement ObjVoid
                    }

                    val th = context.thisObj
                    if (isStatic) {
                        (th as ObjClass).createClassField(name, ObjUnset, false, visibility, null, start, isTransient = isTransient, type = ObjRecord.Type.Delegated).apply {
                            delegate = finalDelegate
                        }
                        context.addItem(name, false, ObjUnset, visibility, recordType = ObjRecord.Type.Delegated, isTransient = isTransient).apply {
                            delegate = finalDelegate
                        }
                    } else if (th is ObjClass) {
                        val cls: ObjClass = th
                        val storageName = "${cls.className}::$name"
                        cls.createField(name, ObjUnset, false, visibility, null, start, declaringClass = cls, isAbstract = isAbstract, isClosed = isClosed, isOverride = isOverride, isTransient = isTransient, type = ObjRecord.Type.Delegated)
                        cls.instanceInitializers += statement(start) { scp ->
                            val accessType2 = scp.resolveQualifiedIdentifier("DelegateAccess.Callable")
                            val initValue2 = delegateExpression.execute(scp)
                            val finalDelegate2 = try {
                                initValue2.invokeInstanceMethod(scp, "bind", Arguments(ObjString(name), accessType2, scp.thisObj))
                            } catch (e: Exception) {
                                initValue2
                            }
                            scp.addItem(storageName, false, ObjUnset, visibility, null, recordType = ObjRecord.Type.Delegated, isAbstract = isAbstract, isClosed = isClosed, isOverride = isOverride, isTransient = isTransient).apply {
                                delegate = finalDelegate2
                            }
                            ObjVoid
                        }
                    } else {
                        context.addItem(name, false, ObjUnset, visibility, recordType = ObjRecord.Type.Delegated, isTransient = isTransient).apply {
                            delegate = finalDelegate
                        }
                    }
                    return@statement ObjVoid
                }

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
                    val stmt = statement {
                        // ObjInstance has a fixed instance scope, so we need to build a closure
                        (thisObj as? ObjInstance)?.let { i ->
                            annotatedFnBody.execute(ClosureScope(this, i.instanceScope))
                        }
                        // other classes can create one-time scope for this rare case:
                            ?: annotatedFnBody.execute(thisObj.autoInstanceScope(this))
                    }
                    context.addExtension(type, name, ObjRecord(stmt, isMutable = false, visibility = visibility, declaringClass = null))
                }
                // regular function/method
                    ?: run {
                        val th = context.thisObj
                        if (!isStatic && th is ObjClass) {
                            // Instance method declared inside a class body: register on the class
                            val cls: ObjClass = th
                            cls.addFn(
                                name,
                                isMutable = true,
                                visibility = visibility,
                                isAbstract = isAbstract,
                                isClosed = isClosed,
                                isOverride = isOverride,
                                pos = start
                            ) {
                                // Execute with the instance as receiver; set caller lexical class for visibility
                                val savedCtx = this.currentClassCtx
                                this.currentClassCtx = cls
                                try {
                                    (thisObj as? ObjInstance)?.let { i ->
                                        annotatedFnBody.execute(ClosureScope(this, i.instanceScope))
                                    } ?: annotatedFnBody.execute(thisObj.autoInstanceScope(this))
                                } finally {
                                    this.currentClassCtx = savedCtx
                                }
                            }
                            // also expose the symbol in the class scope for possible references
                            context.addItem(name, false, annotatedFnBody, visibility)
                            annotatedFnBody
                        } else {
                            // top-level or nested function
                            context.addItem(name, false, annotatedFnBody, visibility)
                        }
                    }
                // as the function can be called from anywhere, we have
                // saved the proper context in the closure
                annotatedFnBody
            }
            if (isStatic) {
                currentInitScope += fnCreateStatement
                NopStatement
            } else
                fnCreateStatement
        }.also {
            val bodyRange = lastParsedBlockRange
            // Also emit a post-parse MiniFunDecl to be robust in case early emission was skipped by some path
            val params = argsDeclaration.params.map { p ->
                MiniParam(
                    name = p.name,
                    type = p.miniType,
                    nameStart = p.pos
                )
            }
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val node = MiniFunDecl(
                range = declRange,
                name = name,
                params = params,
                returnType = returnTypeMini,
                body = bodyRange?.let { MiniBlock(it) },
                doc = declDocLocal,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onExitFunction(cc.currentPos())
            miniSink?.onFunDecl(node)
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
            // Record last parsed block range and notify Mini-AST sink
            val range = MiniRange(startPos, t1.pos)
            lastParsedBlockRange = range
            miniSink?.onBlock(MiniBlock(range))
        }
    }

    private suspend fun parseVarDeclaration(
        isMutable: Boolean,
        visibility: Visibility,
        isAbstract: Boolean = false,
        isClosed: Boolean = false,
        isOverride: Boolean = false,
        isStatic: Boolean = false,
        isExtern: Boolean = false,
        isTransient: Boolean = isTransientFlag
    ): Statement {
        isTransientFlag = false
        val actualExtern = isExtern || (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.isExtern == true
        val nextToken = cc.next()
        val start = nextToken.pos

        if (nextToken.type == Token.Type.LBRACKET) {
            // Destructuring
            if (isStatic) throw ScriptError(start, "static destructuring is not supported")

            val entries = parseArrayLiteral()
            val pattern = ListLiteralRef(entries)

            // Register all names in the pattern
            pattern.forEachVariableWithPos { name, namePos ->
                declareLocalName(name)
                val declRange = MiniRange(namePos, namePos)
                val node = MiniValDecl(
                    range = declRange,
                    name = name,
                    mutable = isMutable,
                    type = null,
                    initRange = null,
                    doc = pendingDeclDoc,
                    nameStart = namePos,
                    isExtern = actualExtern,
                    isStatic = false
                )
                miniSink?.onValDecl(node)
            }
            pendingDeclDoc = null

            val eqToken = cc.next()
            if (eqToken.type != Token.Type.ASSIGN)
                throw ScriptError(eqToken.pos, "destructuring declaration must be initialized")

            val initialExpression = parseStatement(true)
                ?: throw ScriptError(eqToken.pos, "Expected initializer expression")

            val names = mutableListOf<String>()
            pattern.forEachVariable { names.add(it) }

            return statement(start) { context ->
                val value = initialExpression.execute(context)
                for (name in names) {
                    context.addItem(name, true, ObjVoid, visibility, isTransient = isTransient)
                }
                pattern.setAt(start, context, value)
                if (!isMutable) {
                    for (name in names) {
                        val rec = context.objects[name]!!
                        val immutableRec = rec.copy(isMutable = false)
                        context.objects[name] = immutableRec
                        context.localBindings[name] = immutableRec
                        context.updateSlotFor(name, immutableRec)
                    }
                }
                ObjVoid
            }
        }

        if (nextToken.type != Token.Type.ID)
            throw ScriptError(nextToken.pos, "Expected identifier or [ here")
        var name = nextToken.value
        var extTypeName: String? = null
        var nameStartPos: Pos = nextToken.pos
        var receiverMini: MiniTypeRef? = null

        if (cc.peekNextNonWhitespace().type == Token.Type.DOT) {
            cc.skipWsTokens()
            cc.next() // consume dot
            extTypeName = name
            val receiverEnd = Pos(nextToken.pos.source, nextToken.pos.line, nextToken.pos.column + name.length)
            receiverMini = MiniTypeName(
                range = MiniRange(nextToken.pos, receiverEnd),
                segments = listOf(MiniTypeName.Segment(name, MiniRange(nextToken.pos, receiverEnd))),
                nullable = false
            )
            val nameToken = cc.next()
            if (nameToken.type != Token.Type.ID)
                throw ScriptError(nameToken.pos, "Expected identifier after dot in extension declaration")
            name = nameToken.value
            nameStartPos = nameToken.pos
        }

        // Optional explicit type annotation
        val varTypeMini: MiniTypeRef? = if (cc.current().type == Token.Type.COLON) {
            parseTypeDeclarationWithMini().second
        } else null

        val markBeforeEq = cc.savePos()
        cc.skipWsTokens()
        val eqToken = cc.next()
        var setNull = false
        var isProperty = false

        val declaringClassNameCaptured = (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.name

        if (declaringClassNameCaptured != null || extTypeName != null) {
            val mark = cc.savePos()
            cc.restorePos(markBeforeEq)
            cc.skipWsTokens()
            
            // Heuristic: if we see 'get(' or 'set(' or 'private set(' or 'protected set(', 
            // look ahead for a body.
            fun hasAccessorWithBody(): Boolean {
                val t = cc.peekNextNonWhitespace()
                if (t.isId("get") || t.isId("set")) {
                    val saved = cc.savePos()
                    cc.next() // consume get/set
                    val nextToken = cc.peekNextNonWhitespace()
                    if (nextToken.type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        var depth = 1
                        while (cc.hasNext() && depth > 0) {
                            val tt = cc.next()
                            if (tt.type == Token.Type.LPAREN) depth++
                            else if (tt.type == Token.Type.RPAREN) depth--
                        }
                        val next = cc.peekNextNonWhitespace()
                        if (next.type == Token.Type.LBRACE || next.type == Token.Type.ASSIGN) {
                            cc.restorePos(saved)
                            return true
                        }
                    } else if (nextToken.type == Token.Type.LBRACE || nextToken.type == Token.Type.ASSIGN) {
                        cc.restorePos(saved)
                        return true
                    }
                    cc.restorePos(saved)
                } else if (t.isId("private") || t.isId("protected")) {
                    val saved = cc.savePos()
                    cc.next() // consume modifier
                    if (cc.skipWsTokens().isId("set")) {
                        cc.next() // consume set
                        val nextToken = cc.peekNextNonWhitespace()
                        if (nextToken.type == Token.Type.LPAREN) {
                            cc.next() // consume (
                            var depth = 1
                            while (cc.hasNext() && depth > 0) {
                                val tt = cc.next()
                                if (tt.type == Token.Type.LPAREN) depth++
                                else if (tt.type == Token.Type.RPAREN) depth--
                            }
                            val next = cc.peekNextNonWhitespace()
                            if (next.type == Token.Type.LBRACE || next.type == Token.Type.ASSIGN) {
                                cc.restorePos(saved)
                                return true
                            }
                        } else if (nextToken.type == Token.Type.LBRACE || nextToken.type == Token.Type.ASSIGN) {
                            cc.restorePos(saved)
                            return true
                        }
                    }
                    cc.restorePos(saved)
                }
                return false
            }
            
            if (hasAccessorWithBody()) {
                isProperty = true
                cc.restorePos(markBeforeEq)
                // Do not consume eqToken if it's an accessor keyword
            } else {
                cc.restorePos(mark)
            }
        }

        val effectiveEqToken = if (isProperty) null else eqToken

        // Register the local name at compile time so that subsequent identifiers can be emitted as fast locals
        if (!isStatic) declareLocalName(name)

        val isDelegate = if (isAbstract || actualExtern) {
            if (!isProperty && (effectiveEqToken?.type == Token.Type.ASSIGN || effectiveEqToken?.type == Token.Type.BY))
                throw ScriptError(effectiveEqToken.pos, "${if (isAbstract) "abstract" else "extern"} variable $name cannot have an initializer or delegate")
            // Abstract or extern variables don't have initializers
            cc.restorePos(markBeforeEq)
            cc.skipWsTokens()
            setNull = true
            false
        } else if (!isProperty && effectiveEqToken?.type == Token.Type.BY) {
            true
        } else {
            if (!isProperty && effectiveEqToken?.type != Token.Type.ASSIGN) {
                if (!isMutable && (declaringClassNameCaptured == null) && (extTypeName == null))
                    throw ScriptError(start, "val must be initialized")
                else if (!isMutable && declaringClassNameCaptured != null && extTypeName == null) {
                    // lateinit val in class: track it
                    (codeContexts.lastOrNull() as? CodeContext.ClassBody)?.pendingInitializations?.put(name, start)
                    cc.restorePos(markBeforeEq)
                    cc.skipWsTokens()
                    setNull = true
                } else {
                    cc.restorePos(markBeforeEq)
                    cc.skipWsTokens()
                    setNull = true
                }
            }
            false
        }

        val initialExpression = if (setNull || isProperty) null
        else parseStatement(true)
            ?: throw ScriptError(effectiveEqToken!!.pos, "Expected initializer expression")

        // Emit MiniValDecl for this declaration (before execution wiring), attach doc if any
        run {
            val declRange = MiniRange(pendingDeclStart ?: start, cc.currentPos())
            val initR = if (setNull || isProperty) null else MiniRange(effectiveEqToken!!.pos, cc.currentPos())
            val node = MiniValDecl(
                range = declRange,
                name = name,
                mutable = isMutable,
                type = varTypeMini,
                initRange = initR,
                doc = pendingDeclDoc,
                nameStart = nameStartPos,
                receiver = receiverMini,
                isExtern = actualExtern,
                isStatic = isStatic
            )
            miniSink?.onValDecl(node)
            pendingDeclDoc = null
        }

        if (isStatic) {
            // find objclass instance: this is tricky: this code executes in object initializer,
            // when creating instance, but we need to execute it in the class initializer which
            // is missing as for now. Add it to the compiler context?

            currentInitScope += object : Statement() {
                override val pos: Pos = start
                override suspend fun execute(scope: Scope): Obj {
                    val initValue = initialExpression?.execute(scope)?.byValueCopy() ?: ObjNull
                    if (isDelegate) {
                        val accessTypeStr = if (isMutable) "Var" else "Val"
                        val accessType = scope.resolveQualifiedIdentifier("DelegateAccess.$accessTypeStr")
                        val finalDelegate = try {
                            initValue.invokeInstanceMethod(
                                scope,
                                "bind",
                                Arguments(ObjString(name), accessType, scope.thisObj)
                            )
                        } catch (e: Exception) {
                            initValue
                        }
                        (scope.thisObj as ObjClass).createClassField(
                            name,
                            ObjUnset,
                            isMutable,
                            visibility,
                            null,
                            start,
                            isTransient = isTransient,
                            type = ObjRecord.Type.Delegated
                        ).apply {
                            delegate = finalDelegate
                        }
                        // Also expose in current init scope
                        scope.addItem(
                            name,
                            isMutable,
                            ObjUnset,
                            visibility,
                            null,
                            ObjRecord.Type.Delegated,
                            isTransient = isTransient
                        ).apply {
                            delegate = finalDelegate
                        }
                    } else {
                        (scope.thisObj as ObjClass).createClassField(
                            name,
                            initValue,
                            isMutable,
                            visibility,
                            null,
                            start,
                            isTransient = isTransient
                        )
                        scope.addItem(name, isMutable, initValue, visibility, null, ObjRecord.Type.Field, isTransient = isTransient)
                    }
                    return ObjVoid
                }
            }
            return NopStatement
        }

        // Check for accessors if it is a class member
        var getter: Statement? = null
        var setter: Statement? = null
        var setterVisibility: Visibility? = null
        if (declaringClassNameCaptured != null || extTypeName != null) {
            while (true) {
                val t = cc.skipWsTokens()
                if (t.isId("get")) {
                    val getStart = cc.currentPos()
                    cc.next() // consume 'get'
                    if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        cc.requireToken(Token.Type.RPAREN)
                    }
                    miniSink?.onEnterFunction(null)
                    getter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        cc.skipWsTokens()
                        inCodeContext(CodeContext.Function("<getter>")) {
                            parseBlock()
                        }
                    } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                        cc.skipWsTokens()
                        cc.next() // consume '='
                        inCodeContext(CodeContext.Function("<getter>")) {
                            val expr = parseExpression()
                                ?: throw ScriptError(cc.current().pos, "Expected getter expression")
                            expr
                        }
                    } else {
                        throw ScriptError(cc.current().pos, "Expected { or = after get()")
                    }
                    miniSink?.onExitFunction(cc.currentPos())
                } else if (t.isId("set")) {
                    val setStart = cc.currentPos()
                    cc.next() // consume 'set'
                    var setArgName = "it"
                    if (cc.peekNextNonWhitespace().type == Token.Type.LPAREN) {
                        cc.next() // consume (
                        setArgName = cc.requireToken(Token.Type.ID, "Expected setter argument name").value
                        cc.requireToken(Token.Type.RPAREN)
                    }
                    miniSink?.onEnterFunction(null)
                    setter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                        cc.skipWsTokens()
                        val body = inCodeContext(CodeContext.Function("<setter>")) {
                            parseBlock()
                        }
                        object : Statement() {
                            override val pos: Pos = body.pos
                            override suspend fun execute(scope: Scope): Obj {
                                val value = scope.args.list.firstOrNull() ?: ObjNull
                                scope.addItem(setArgName, true, value, recordType = ObjRecord.Type.Argument)
                                return body.execute(scope)
                            }
                        }
                    } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                        cc.skipWsTokens()
                        cc.next() // consume '='
                        val expr = inCodeContext(CodeContext.Function("<setter>")) {
                            parseExpression()
                                ?: throw ScriptError(cc.current().pos, "Expected setter expression")
                        }
                        val st = expr
                        object : Statement() {
                            override val pos: Pos = st.pos
                            override suspend fun execute(scope: Scope): Obj {
                                val value = scope.args.list.firstOrNull() ?: ObjNull
                                scope.addItem(setArgName, true, value, recordType = ObjRecord.Type.Argument)
                                return st.execute(scope)
                            }
                        }
                    } else {
                        throw ScriptError(cc.current().pos, "Expected { or = after set(...)")
                    }
                    miniSink?.onExitFunction(cc.currentPos())
                } else if (t.isId("private") || t.isId("protected")) {
                    val vis = if (t.isId("private")) Visibility.Private else Visibility.Protected
                    val mark = cc.savePos()
                    cc.next() // consume modifier
                    if (cc.skipWsTokens().isId("set")) {
                        cc.next() // consume 'set'
                        setterVisibility = vis
                        if (cc.skipWsTokens().type == Token.Type.LPAREN) {
                            cc.next() // consume '('
                            val setArg = cc.requireToken(Token.Type.ID, "Expected setter argument name")
                            cc.requireToken(Token.Type.RPAREN)
                            miniSink?.onEnterFunction(null)
                            val finalSetter = if (cc.peekNextNonWhitespace().type == Token.Type.LBRACE) {
                                cc.skipWsTokens()
                                val body = inCodeContext(CodeContext.Function("<setter>")) {
                                    parseBlock()
                                }
                                object : Statement() {
                                    override val pos: Pos = body.pos
                                    override suspend fun execute(scope: Scope): Obj {
                                        val value = scope.args.list.firstOrNull() ?: ObjNull
                                        scope.addItem(setArg.value, true, value, recordType = ObjRecord.Type.Argument)
                                        return body.execute(scope)
                                    }
                                }
                            } else if (cc.peekNextNonWhitespace().type == Token.Type.ASSIGN) {
                                cc.skipWsTokens()
                                cc.next() // consume '='
                                val st = inCodeContext(CodeContext.Function("<setter>")) {
                                    parseExpression() ?: throw ScriptError(
                                        cc.current().pos,
                                        "Expected setter expression"
                                    )
                                }
                                object : Statement() {
                                    override val pos: Pos = st.pos
                                    override suspend fun execute(scope: Scope): Obj {
                                        val value = scope.args.list.firstOrNull() ?: ObjNull
                                        scope.addItem(setArg.value, true, value, recordType = ObjRecord.Type.Argument)
                                        return st.execute(scope)
                                    }
                                }
                            } else {
                                throw ScriptError(cc.current().pos, "Expected { or = after set(...)")
                            }
                            setter = finalSetter
                            miniSink?.onExitFunction(cc.currentPos())
                        } else {
                            // private set without body: setter remains null, visibility is restricted
                        }
                    } else {
                        cc.restorePos(mark)
                        break
                    }
                } else break
            }
            if (getter != null || setter != null) {
                if (isMutable) {
                    if (getter == null || setter == null) {
                        throw ScriptError(start, "var property must have both get() and set()")
                    }
                } else {
                    if (setter != null || setterVisibility != null)
                        throw ScriptError(start, "val property cannot have a setter or restricted visibility set (name: $name)")
                    if (getter == null)
                        throw ScriptError(start, "val property with accessors must have a getter (name: $name)")
                }
            } else if (setterVisibility != null && !isMutable) {
                throw ScriptError(start, "val field cannot have restricted visibility set (name: $name)")
            }
        }

        return statement(start) { context ->
            if (extTypeName != null) {
                val prop = if (getter != null || setter != null) {
                    ObjProperty(name, getter, setter)
                } else {
                    // Simple val extension with initializer
                    val initExpr = initialExpression ?: throw ScriptError(start, "Extension val must be initialized")
                    ObjProperty(name, statement(initExpr.pos) { scp -> initExpr.execute(scp) }, null)
                }

                val type = context[extTypeName]?.value ?: context.raiseSymbolNotFound("class $extTypeName not found")
                if (type !is ObjClass) context.raiseClassCastError("$extTypeName is not the class instance")

                context.addExtension(type, name, ObjRecord(prop, isMutable = false, visibility = visibility, writeVisibility = setterVisibility, declaringClass = null, type = ObjRecord.Type.Property))

                return@statement prop
            }
            // In true class bodies (not inside a function), store fields under a class-qualified key to support MI collisions
            // Do NOT infer declaring class from runtime thisObj here; only the compile-time captured
            // ClassBody qualifies for class-field storage. Otherwise, this is a plain local.
            isProperty = getter != null || setter != null
            val declaringClassName = declaringClassNameCaptured
            if (declaringClassName == null) {
                if (context.containsLocal(name))
                    throw ScriptError(start, "Variable $name is already defined")
            }

            // Register the local name so subsequent identifiers can be emitted as fast locals
            if (!isStatic) declareLocalName(name)

            if (isDelegate) {
                val declaringClassName = declaringClassNameCaptured
                if (declaringClassName != null) {
                    val storageName = "$declaringClassName::$name"
                    val isClassScope = context.thisObj is ObjClass && (context.thisObj !is ObjInstance)
                    if (isClassScope) {
                        val cls = context.thisObj as ObjClass
                        cls.createField(
                            name,
                            ObjUnset,
                            isMutable,
                            visibility,
                            setterVisibility,
                            start,
                            isTransient = isTransient,
                            type = ObjRecord.Type.Delegated,
                            isAbstract = isAbstract,
                            isClosed = isClosed,
                            isOverride = isOverride
                        )
                        cls.instanceInitializers += statement(start) { scp ->
                            val initValue = initialExpression!!.execute(scp)
                            val accessTypeStr = if (isMutable) "Var" else "Val"
                            val accessType = scp.resolveQualifiedIdentifier("DelegateAccess.$accessTypeStr")
                            val finalDelegate = try {
                                initValue.invokeInstanceMethod(scp, "bind", Arguments(ObjString(name), accessType, scp.thisObj))
                            } catch (e: Exception) {
                                initValue
                            }
                            scp.addItem(
                                storageName, isMutable, ObjUnset, visibility, setterVisibility,
                                recordType = ObjRecord.Type.Delegated,
                                isAbstract = isAbstract,
                                isClosed = isClosed,
                                isOverride = isOverride,
                                isTransient = isTransient
                            ).apply {
                                delegate = finalDelegate
                            }
                            ObjVoid
                        }
                        return@statement ObjVoid
                    } else {
                        val initValue = initialExpression!!.execute(context)
                        val accessTypeStr = if (isMutable) "Var" else "Val"
                        val accessType = context.resolveQualifiedIdentifier("DelegateAccess.$accessTypeStr")
                        val finalDelegate = try {
                            initValue.invokeInstanceMethod(context, "bind", Arguments(ObjString(name), accessType, context.thisObj))
                        } catch (e: Exception) {
                            initValue
                        }
                        val rec = context.addItem(
                            storageName, isMutable, ObjUnset, visibility, setterVisibility,
                            recordType = ObjRecord.Type.Delegated,
                            isAbstract = isAbstract,
                            isClosed = isClosed,
                            isOverride = isOverride,
                            isTransient = isTransient
                        )
                        rec.delegate = finalDelegate
                        return@statement finalDelegate
                    }
                } else {
                    val initValue = initialExpression!!.execute(context)
                    val accessTypeStr = if (isMutable) "Var" else "Val"
                    val accessType = context.resolveQualifiedIdentifier("DelegateAccess.$accessTypeStr")
                    val finalDelegate = try {
                        initValue.invokeInstanceMethod(context, "bind", Arguments(ObjString(name), accessType, ObjNull))
                    } catch (e: Exception) {
                        initValue
                    }
                    val rec = context.addItem(
                        name, isMutable, ObjUnset, visibility, setterVisibility,
                        recordType = ObjRecord.Type.Delegated,
                        isAbstract = isAbstract,
                        isClosed = isClosed,
                        isOverride = isOverride,
                        isTransient = isTransient
                    )
                    rec.delegate = finalDelegate
                    return@statement finalDelegate
                }
            } else if (getter != null || setter != null) {
                val declaringClassName = declaringClassNameCaptured!!
                val storageName = "$declaringClassName::$name"
                val prop = ObjProperty(name, getter, setter)

                // If we are in class scope now (defining instance field), defer initialization to instance time
                val isClassScope = context.thisObj is ObjClass && (context.thisObj !is ObjInstance)
                if (isClassScope) {
                    val cls = context.thisObj as ObjClass
                    // Register in class members for reflection/MRO/satisfaction checks
                    if (isProperty) {
                        cls.addProperty(
                            name,
                            visibility = visibility,
                            writeVisibility = setterVisibility,
                            isAbstract = isAbstract,
                            isClosed = isClosed,
                            isOverride = isOverride,
                            pos = start,
                            prop = prop
                        )
                    } else {
                        cls.createField(
                            name,
                            ObjNull,
                            isMutable = isMutable,
                            visibility = visibility,
                            writeVisibility = setterVisibility,
                            isAbstract = isAbstract,
                            isClosed = isClosed,
                            isOverride = isOverride,
                            isTransient = isTransient,
                            type = ObjRecord.Type.Field
                        )
                    }

                    // Register the property/field initialization thunk
                    if (!isAbstract) {
                        cls.instanceInitializers += statement(start) { scp ->
                            scp.addItem(
                                storageName,
                                isMutable,
                                prop,
                                visibility,
                                setterVisibility,
                                recordType = ObjRecord.Type.Property,
                                isAbstract = isAbstract,
                                isClosed = isClosed,
                                isOverride = isOverride
                            )
                            ObjVoid
                        }
                    }
                    ObjVoid
                } else {
                    // We are in instance scope already: perform initialization immediately
                    context.addItem(
                        storageName, isMutable, prop, visibility, setterVisibility,
                        recordType = ObjRecord.Type.Property,
                        isAbstract = isAbstract,
                        isClosed = isClosed,
                        isOverride = isOverride,
                        isTransient = isTransient
                    )
                    prop
                }
            } else {
                    val isLateInitVal = !isMutable && initialExpression == null
                    if (declaringClassName != null && !isStatic) {
                        val storageName = "$declaringClassName::$name"
                        // If we are in class scope now (defining instance field), defer initialization to instance time
                        val isClassScope = context.thisObj is ObjClass && (context.thisObj !is ObjInstance)
                        if (isClassScope) {
                            val cls = context.thisObj as ObjClass
                            // Register in class members for reflection/MRO/satisfaction checks
                            cls.createField(
                                name,
                                ObjNull,
                                isMutable = isMutable,
                                visibility = visibility,
                                writeVisibility = setterVisibility,
                                isAbstract = isAbstract,
                                isClosed = isClosed,
                                isOverride = isOverride,
                                pos = start,
                                isTransient = isTransient,
                                type = ObjRecord.Type.Field
                            )

                            // Defer: at instance construction, evaluate initializer in instance scope and store under mangled name
                            if (!isAbstract) {
                                val initStmt = statement(start) { scp ->
                                    val initValue =
                                        initialExpression?.execute(scp)?.byValueCopy()
                                            ?: if (isLateInitVal) ObjUnset else ObjNull
                                    // Preserve mutability of declaration: do NOT use addOrUpdateItem here, as it creates mutable records
                                    scp.addItem(
                                        storageName, isMutable, initValue, visibility, setterVisibility,
                                        recordType = ObjRecord.Type.Field,
                                        isAbstract = isAbstract,
                                        isClosed = isClosed,
                                        isOverride = isOverride,
                                        isTransient = isTransient
                                    )
                                    ObjVoid
                                }
                                cls.instanceInitializers += initStmt
                            }
                            ObjVoid
                        } else {
                            // We are in instance scope already: perform initialization immediately
                            val initValue =
                                initialExpression?.execute(context)?.byValueCopy() ?: if (isLateInitVal) ObjUnset else ObjNull
                            // Preserve mutability of declaration: create record with correct mutability
                            context.addItem(
                                storageName, isMutable, initValue, visibility, setterVisibility,
                                recordType = ObjRecord.Type.Field,
                                isAbstract = isAbstract,
                                isClosed = isClosed,
                                isOverride = isOverride,
                                isTransient = isTransient
                            )
                            initValue
                        }
                    } else {
                        // Not in class body: regular local/var declaration
                        val initValue = initialExpression?.execute(context)?.byValueCopy() ?: ObjNull
                        context.addItem(name, isMutable, initValue, visibility, recordType = ObjRecord.Type.Other, isTransient = isTransient)
                        initValue
                    }
            }
        }
    }

    data class Operator(
        val tokenType: Token.Type,
        val priority: Int, val arity: Int = 2,
        val generate: (Pos, ObjRef, ObjRef) -> ObjRef
    ) {
//        fun isLeftAssociative() = tokenType != Token.Type.OR && tokenType != Token.Type.AND

        companion object

    }

    companion object {

        suspend fun compile(source: Source, importManager: ImportProvider): Script {
            return Compiler(CompilerContext(parseLyng(source)), importManager).parseScript()
        }

        /**
         * Compile [source] while streaming a Mini-AST into the provided [sink].
         * When [sink] is null, behaves like [compile].
         */
        suspend fun compileWithMini(
            source: Source,
            importManager: ImportProvider,
            sink: MiniAstSink?
        ): Script {
            return Compiler(
                CompilerContext(parseLyng(source)),
                importManager,
                Settings(miniAstSink = sink)
            ).parseScript()
        }

        /** Convenience overload to compile raw [code] with a Mini-AST [sink]. */
        suspend fun compileWithMini(code: String, sink: MiniAstSink?): Script =
            compileWithMini(Source("<eval>", code), Script.defaultImportManager, sink)

        private var lastPriority = 0

        // Helpers for conservative constant folding (literal-only). Only pure, side-effect-free ops.
        private fun constOf(r: ObjRef): Obj? = (r as? ConstRef)?.constValue

        private fun foldBinary(op: BinOp, aRef: ObjRef, bRef: ObjRef): Obj? {
            val a = constOf(aRef) ?: return null
            val b = constOf(bRef) ?: return null
            return when (op) {
                // Boolean logic
                BinOp.OR -> if (a is ObjBool && b is ObjBool) if (a.value || b.value) ObjTrue else ObjFalse else null
                BinOp.AND -> if (a is ObjBool && b is ObjBool) if (a.value && b.value) ObjTrue else ObjFalse else null

                // Equality and comparisons for ints/strings/chars
                BinOp.EQ -> when {
                    a is ObjInt && b is ObjInt -> if (a.value == b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value == b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value == b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.NEQ -> when {
                    a is ObjInt && b is ObjInt -> if (a.value != b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value != b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value != b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.LT -> when {
                    a is ObjInt && b is ObjInt -> if (a.value < b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value < b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value < b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.LTE -> when {
                    a is ObjInt && b is ObjInt -> if (a.value <= b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value <= b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value <= b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.GT -> when {
                    a is ObjInt && b is ObjInt -> if (a.value > b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value > b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value > b.value) ObjTrue else ObjFalse
                    else -> null
                }

                BinOp.GTE -> when {
                    a is ObjInt && b is ObjInt -> if (a.value >= b.value) ObjTrue else ObjFalse
                    a is ObjString && b is ObjString -> if (a.value >= b.value) ObjTrue else ObjFalse
                    a is ObjChar && b is ObjChar -> if (a.value >= b.value) ObjTrue else ObjFalse
                    else -> null
                }

                // Arithmetic for ints only (keep semantics simple at compile time)
                BinOp.PLUS -> when {
                    a is ObjInt && b is ObjInt -> ObjInt.of(a.value + b.value)
                    a is ObjString && b is ObjString -> ObjString(a.value + b.value)
                    else -> null
                }

                BinOp.MINUS -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value - b.value) else null
                BinOp.STAR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value * b.value) else null
                BinOp.SLASH -> if (a is ObjInt && b is ObjInt && b.value != 0L) ObjInt.of(a.value / b.value) else null
                BinOp.PERCENT -> if (a is ObjInt && b is ObjInt && b.value != 0L) ObjInt.of(a.value % b.value) else null

                // Bitwise for ints
                BinOp.BAND -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value and b.value) else null
                BinOp.BXOR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value xor b.value) else null
                BinOp.BOR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value or b.value) else null
                BinOp.SHL -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value shl (b.value.toInt() and 63)) else null
                BinOp.SHR -> if (a is ObjInt && b is ObjInt) ObjInt.of(a.value shr (b.value.toInt() and 63)) else null

                // Non-folded / side-effecting or type-dependent ops
                BinOp.EQARROW, BinOp.REF_EQ, BinOp.REF_NEQ, BinOp.MATCH, BinOp.NOTMATCH,
                BinOp.IN, BinOp.NOTIN, BinOp.IS, BinOp.NOTIS, BinOp.SHUTTLE -> null
            }
        }

        private fun foldUnary(op: UnaryOp, aRef: ObjRef): Obj? {
            val a = constOf(aRef) ?: return null
            return when (op) {
                UnaryOp.NOT -> if (a is ObjBool) if (!a.value) ObjTrue else ObjFalse else null
                UnaryOp.NEGATE -> when (a) {
                    is ObjInt -> ObjInt.of(-a.value)
                    is ObjReal -> ObjReal.of(-a.value)
                    else -> null
                }

                UnaryOp.BITNOT -> if (a is ObjInt) ObjInt.of(a.value.inv()) else null
            }
        }

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
            Operator(Token.Type.IFNULLASSIGN, lastPriority) { pos, a, b ->
                AssignIfNullRef(a, b, pos)
            },
            // logical 1
            Operator(Token.Type.OR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.OR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                LogicalOrRef(a, b)
            },
            // logical 2
            Operator(Token.Type.AND, ++lastPriority) { _, a, b ->
                LogicalAndRef(a, b)
            },
            // bitwise or/xor/and (tighter than &&, looser than equality)
            Operator(Token.Type.BITOR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BOR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BOR, a, b)
            },
            Operator(Token.Type.BITXOR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BXOR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BXOR, a, b)
            },
            Operator(Token.Type.BITAND, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.BAND, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.BAND, a, b)
            },
            // equality/not equality and related
            Operator(Token.Type.EQARROW, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.EQARROW, a, b)
            },
            Operator(Token.Type.EQ, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.EQ, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.EQ, a, b)
            },
            Operator(Token.Type.NEQ, lastPriority) { _, a, b ->
                foldBinary(BinOp.NEQ, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
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
                foldBinary(BinOp.LTE, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.LTE, a, b)
            },
            Operator(Token.Type.LT, lastPriority) { _, a, b ->
                foldBinary(BinOp.LT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.LT, a, b)
            },
            Operator(Token.Type.GTE, lastPriority) { _, a, b ->
                foldBinary(BinOp.GTE, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.GTE, a, b)
            },
            Operator(Token.Type.GT, lastPriority) { _, a, b ->
                foldBinary(BinOp.GT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
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
            // casts: as / as?
            Operator(Token.Type.AS, lastPriority) { pos, a, b ->
                CastRef(a, b, false, pos)
            },
            Operator(Token.Type.ASNULL, lastPriority) { pos, a, b ->
                CastRef(a, b, true, pos)
            },

            Operator(Token.Type.ELVIS, ++lastPriority, 2) { _, a, b ->
                ElvisRef(a, b)
            },

            // shuttle <=>
            Operator(Token.Type.SHUTTLE, ++lastPriority) { _, a, b ->
                BinaryOpRef(BinOp.SHUTTLE, a, b)
            },
            // shifts (tighter than shuttle, looser than +/-)
            Operator(Token.Type.SHL, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.SHL, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SHL, a, b)
            },
            Operator(Token.Type.SHR, lastPriority) { _, a, b ->
                foldBinary(BinOp.SHR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SHR, a, b)
            },
            // arithmetic
            Operator(Token.Type.PLUS, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.PLUS, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.PLUS, a, b)
            },
            Operator(Token.Type.MINUS, lastPriority) { _, a, b ->
                foldBinary(BinOp.MINUS, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.MINUS, a, b)
            },
            Operator(Token.Type.STAR, ++lastPriority) { _, a, b ->
                foldBinary(BinOp.STAR, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.STAR, a, b)
            },
            Operator(Token.Type.SLASH, lastPriority) { _, a, b ->
                foldBinary(BinOp.SLASH, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.SLASH, a, b)
            },
            Operator(Token.Type.PERCENT, lastPriority) { _, a, b ->
                foldBinary(BinOp.PERCENT, a, b)?.let { return@Operator ConstRef(it.asReadonly) }
                BinaryOpRef(BinOp.PERCENT, a, b)
            },
        )

//        private val assigner = allOps.first { it.tokenType == Token.Type.ASSIGN }
//
//        fun performAssignment(context: Context, left: Accessor, right: Accessor) {
//            assigner.generate(context.pos, left, right)
//        }

        // Compute levels from the actual operator table rather than relying on
        // the mutable construction counter. This prevents accidental inflation
        // of precedence depth that could lead to deep recursive descent and
        // StackOverflowError during parsing.
        val lastLevel = (allOps.maxOf { it.priority }) + 1

        val byLevel: List<Map<Token.Type, Operator>> = (0..<lastLevel).map { l ->
            allOps.filter { it.priority == l }.associateBy { it.tokenType }
        }

        suspend fun compile(code: String): Script = compile(Source("<eval>", code), Script.defaultImportManager)

        /**
         * The keywords that stop processing of expression term
         */
        val stopKeywords =
            setOf(
                "break", "continue", "return", "if", "when", "do", "while", "for", "class",
                "private", "protected", "val", "var", "fun", "fn", "static", "init", "enum"
            )
    }
}

suspend fun eval(code: String) = compile(code).execute()
suspend fun evalNamed(name: String, code: String, importManager: ImportManager = Script.defaultImportManager) =
    compile(Source(name,code), importManager).execute()
