package net.sergeych.lyng

internal class CompilerContext(val tokens: List<Token>) {
    val labels = mutableSetOf<String>()

    var breakFound = false
        private set

    var loopLevel = 0
        private set

    inline fun <T> parseLoop(f: () -> T): Pair<Boolean, T> {
        if (++loopLevel == 0) breakFound = false
        val result = f()
        return Pair(breakFound, result).also {
            --loopLevel
        }
    }

    var currentIndex = 0

    fun hasNext() = currentIndex < tokens.size
    fun hasPrevious() = currentIndex > 0
    fun next() = tokens.getOrElse(currentIndex) { throw IllegalStateException("No next token") }.also { currentIndex++ }
    fun previous() = if (!hasPrevious()) throw IllegalStateException("No previous token") else tokens[--currentIndex]

    fun savePos() = currentIndex
    fun restorePos(pos: Int) {
        currentIndex = pos
    }

    fun ensureLabelIsValid(pos: Pos, label: String) {
        if (label !in labels)
            throw ScriptError(pos, "Undefined label '$label'")
    }

    @Suppress("unused")
    fun requireId() = requireToken(Token.Type.ID, "identifier is required")

    fun requireToken(type: Token.Type, message: String = "required ${type.name}"): Token =
        next().also {
            if (type != it.type) throw ScriptError(it.pos, message)
        }

    @Suppress("unused")
    fun syntaxError(at: Pos, message: String = "Syntax error"): Nothing {
        throw ScriptError(at, message)
    }

    fun currentPos() =
        if (hasNext()) next().pos.also { previous() }
        else previous().pos.also { next() }

    /**
     * Skips next token if its type is `tokenType`, returns `true` if so.
     * @param errorMessage message to throw if next token is not `tokenType`
     * @param isOptional if `true` and token is not of `tokenType`, just return `false` and does not skip it
     * @return `true` if the token was skipped
     * @throws ScriptError if [isOptional] is `false` and next token is not of [tokenType]
     */
    fun skipTokenOfType(
        tokenType: Token.Type,
        errorMessage: String = "expected ${tokenType.name}",
        isOptional: Boolean = false
    ): Boolean {
        val t = next()
        return if (t.type != tokenType) {
            if (!isOptional) {
                println("unexpected: $t (needed $tokenType)")
                throw ScriptError(t.pos, errorMessage)
            } else {
                previous()
                false
            }
        } else true
    }

    @Suppress("unused")
    fun skipTokens(vararg tokenTypes: Token.Type) {
        while (next().type in tokenTypes) { /**/
        }
        previous()
    }


    fun ifNextIs(typeId: Token.Type, f: (Token) -> Unit): Boolean {
        val t = next()
        return if (t.type == typeId) {
            f(t)
            true
        } else {
            previous()
            false
        }
    }

    inline fun addBreak() {
        breakFound = true
    }

    /**
     * Return value of the next token if it is an identifier, null otherwise.
     * Does not change position.
     */
    fun nextIdValue(): String? {
        return if (hasNext()) {
            val nt = tokens[currentIndex]
            if (nt.type == Token.Type.ID)
                nt.value
            else null
        } else null
    }

    @Suppress("unused")
    fun current(): Token = tokens[currentIndex]

    /**
     * If the token at current position plus offset (could be negative) exists, returns it, otherwise returns null.
     */
    fun atOffset(offset: Int): Token? =
        if (currentIndex + offset in tokens.indices) tokens[currentIndex + offset] else null

    /**
     * Scan backwards as deep as specified looking for visibility token. Does not change position.
     */
    fun getVisibility(default: Visibility = Visibility.Public, depths: Int = 2): Visibility {
        for( i in -depths .. -1) {
            when( atOffset(i)?.type) {
                Token.Type.PROTECTED -> return Visibility.Protected
                Token.Type.PRIVATE -> return Visibility.Private
                else -> {}
            }
        }
        return default
    }

//    data class ReturnScope(val needCatch: Boolean = false)

//    private val

//    fun startReturnScope(): ReturnScope {
//        return ReturnScope()
//    }

}