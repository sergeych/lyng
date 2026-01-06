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
package net.sergeych.lyng.format

/**
 * Lightweight, PSI‑free formatter for Lyng source code.
 *
 * Phase 1 focuses on indentation from scratch (idempotent). Spacing/wrapping may be
 * extended later based on [LyngFormatConfig] flags.
 */
object LyngFormatter {

    private fun startsWithWord(s: String, w: String): Boolean =
        s.startsWith(w) && s.getOrNull(w.length)?.let { !it.isLetterOrDigit() && it != '_' } != false

    private fun isPropertyAccessor(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (startsWithWord(t, "get") || startsWithWord(t, "set")) return true
        if (startsWithWord(t, "private")) {
            val rest = t.substring("private".length).trimStart()
            if (startsWithWord(rest, "set")) return true
        }
        if (startsWithWord(t, "protected")) {
            val rest = t.substring("protected".length).trimStart()
            if (startsWithWord(rest, "set")) return true
        }
        return false
    }

    private fun isAccessorRelated(code: String): Boolean {
        val t = code.trim()
        if (t.isEmpty()) return false
        if (isPropertyAccessor(t)) return true

        // If it contains 'fun' or 'fn' as a word, it's probably a function declaration, not an accessor
        if (Regex("\\b(fun|fn)\\b").containsMatchIn(t)) return false

        val hasDecl = startsWithWord(t, "var") || startsWithWord(t, "val") ||
                startsWithWord(t, "private") || startsWithWord(t, "protected") ||
                startsWithWord(t, "override") || startsWithWord(t, "public")

        if (hasDecl) {
            val getSetMatch = Regex("\\b(get|set)\\b").find(t)
            if (getSetMatch != null) {
                // Check it's not part of an assignment to the property itself (e.g. val x = get())
                val equalIndex = t.indexOf('=')
                if (equalIndex == -1 || equalIndex > getSetMatch.range.first) {
                    return true
                }
            }
        }
        return false
    }

    /** Returns the input with indentation recomputed from scratch, line by line. */
    fun reindent(text: String, config: LyngFormatConfig = LyngFormatConfig()): String {
        // Normalize tabs to spaces globally before any transformation; results must contain no tabs
        val normalized = if (text.indexOf('\t') >= 0) text.replace("\t", " ".repeat(config.indentSize)) else text
        val lines = normalized.split('\n')
        val sb = StringBuilder(text.length + lines.size)
        var blockLevel = 0
        var parenBalance = 0
        var bracketBalance = 0
        var prevBracketContinuation = false
        var inBlockComment = false
        val extraIndents = mutableListOf<Int>()
        // We don't keep per-"[" base alignment; continuation rules define alignment.

        fun updateBalances(ch: Char) {
            when (ch) {
                '{' -> blockLevel++
                '}' -> if (blockLevel > 0) blockLevel--
                '(' -> parenBalance++
                ')' -> if (parenBalance > 0) parenBalance--
                '[' -> bracketBalance++
                ']' -> if (bracketBalance > 0) bracketBalance--
            }
        }

        fun indentOf(level: Int, continuation: Int): String =
            // Always produce spaces; tabs are not allowed in resulting code
            " ".repeat(level * config.indentSize + continuation)

        var awaitingExtraIndent = 0
        fun isControlHeaderNoBrace(s: String): Boolean {
            val t = s.trim()
            if (t.isEmpty()) return false
            // match: if (...) | else if (...) | else
            val isIf = Regex("^if\\s*\\(.*\\)\\s*$").matches(t)
            val isElseIf = Regex("^else\\s+if\\s*\\(.*\\)\\s*$").matches(t)
            val isElse = t == "else"
            if (isIf || isElseIf || isElse) return true

            // property accessors ending with ) or =
            if (isAccessorRelated(t)) {
                return if (t.contains('=')) t.endsWith('=') else t.endsWith(')')
            }
            return false
        }

        for ((i, rawLine) in lines.withIndex()) {
            val (parts, nextInBlockComment) = splitIntoParts(rawLine, inBlockComment)
            val code = parts.filter { it.type == PartType.Code }.joinToString("") { it.text }
            val trimmedStart = code.dropWhile { it == ' ' || it == '\t' }
            val trimmedLine = rawLine.trim()

            // Compute effective indent level for this line
            val currentExtraIndent = extraIndents.sum()
            var effectiveLevel = blockLevel + currentExtraIndent

            val isAccessor = !inBlockComment && isPropertyAccessor(trimmedStart)
            if (isAccessor) effectiveLevel += 1

            if (inBlockComment) {
                if (!trimmedLine.startsWith("*/")) {
                    effectiveLevel += 1
                }
            } else if (trimmedStart.startsWith("}")) {
                effectiveLevel = (effectiveLevel - 1).coerceAtLeast(0)
            }
            // else/catch/finally should align with the parent block level; no extra dedent here,
            // because the preceding '}' has already reduced [blockLevel] appropriately.

            // Single-line control header (if/else/else if) without braces: indent the next
            // non-empty, non-'}', non-'else' line by one extra level
            val applyAwaiting = awaitingExtraIndent > 0 && trimmedStart.isNotEmpty() &&
                    !trimmedStart.startsWith("else") && !trimmedStart.startsWith("}")
            if (applyAwaiting) effectiveLevel += awaitingExtraIndent

            val firstChar = trimmedStart.firstOrNull()
            // While inside parentheses, continuation applies scaled by nesting level
            val parenContLevels = if (parenBalance > 0 && firstChar != ')') parenBalance else 0
            val continuation = when {
                // One-shot continuation when previous line ended with '[' to align first element
                prevBracketContinuation && firstChar != ']' -> config.continuationIndentSize
                // While inside brackets, continuation applies (single unit) except on the closing line
                bracketBalance > 0 && firstChar != ']' -> config.continuationIndentSize
                // While inside parentheses, continuation applies scaled by nesting level
                parenContLevels > 0 -> config.continuationIndentSize * parenContLevels
                trimmedStart.startsWith(".") -> config.indentSize
                else -> 0
            }

            // Special rule: inside bracket lists, do not add base block indent for element lines.
            if (bracketBalance > 0 && firstChar != ']') {
                effectiveLevel = 0
            }

            // Replace leading whitespace with the exact target indent; but keep fully blank lines truly empty
            val contentStart = rawLine.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) rawLine.length else it }
            var content = rawLine.substring(contentStart)
            // Collapse spaces right after an opening '[' to avoid "[    1"; make it "[1"
            if (content.startsWith("[")) {
                content = "[" + content.drop(1).trimStart()
            }
            // Normalize empty block on a single line: "{   }" -> "{}" (safe, idempotent)
            run {
                val t = content.trim()
                if (t.length >= 2 && t.first() == '{' && t.last() == '}' && t.substring(1, t.length - 1).isBlank()) {
                    content = "{}"
                }
            }
            // Determine base indent using structural level and continuation only (spaces only)
            val indentString = indentOf(effectiveLevel, continuation)
            if (content.isNotEmpty()) {
                sb.append(indentString).append(content)
            }

            // New line (keep EOF semantics similar to input)
            if (i < lines.lastIndex) sb.append('\n')

            val oldBlockLevel = blockLevel
            // Update balances using this line's code content
            for (part in parts) {
                if (part.type == PartType.Code) {
                    for (ch in part.text) updateBalances(ch)
                }
            }
            val newBlockLevel = blockLevel
            if (newBlockLevel > oldBlockLevel) {
                val isAccessorRelatedLine = isAccessor || (!inBlockComment && isAccessorRelated(code))
                val addedThisLine = (if (applyAwaiting) awaitingExtraIndent else 0) + (if (isAccessorRelatedLine) 1 else 0)
                repeat(newBlockLevel - oldBlockLevel) {
                    extraIndents.add(addedThisLine)
                }
            } else if (newBlockLevel < oldBlockLevel) {
                repeat(oldBlockLevel - newBlockLevel) {
                    if (extraIndents.isNotEmpty()) extraIndents.removeAt(extraIndents.size - 1)
                }
            }

            inBlockComment = nextInBlockComment

            // Update awaitingExtraIndent based on current line
            if (applyAwaiting && trimmedStart.isNotEmpty()) {
                // we have just applied it.
                val endsWithBrace = code.trimEnd().endsWith("{")
                if (!endsWithBrace && isControlHeaderNoBrace(code)) {
                    // It's another header, increment
                    val isAccessorRelatedLine = isAccessor || (!inBlockComment && isAccessorRelated(code))
                    awaitingExtraIndent += if (isAccessorRelatedLine) 2 else 1
                } else {
                    // It's the body, reset
                    awaitingExtraIndent = 0
                }
            } else {
                // start awaiting if current line is a control header without '{'
                val endsWithBrace = code.trimEnd().endsWith("{")
                if (!endsWithBrace && isControlHeaderNoBrace(code)) {
                    val isAccessorRelatedLine = isAccessor || (!inBlockComment && isAccessorRelated(code))
                    awaitingExtraIndent = if (isAccessorRelatedLine) 2 else 1
                }
            }

            // Prepare one-shot bracket continuation if the current line ends with '['
            val endsWithBracket = code.trimEnd().endsWith("[")
            if (endsWithBracket) {
                // One-shot continuation for the very next line
                prevBracketContinuation = true
            } else {
                // Reset the one-shot flag if it was used or if line doesn't end with '['
                prevBracketContinuation = false
            }
        }
        return sb.toString()
    }

    fun format(text: String, config: LyngFormatConfig = LyngFormatConfig()): String {
        // Phase 1: indentation
        val indented = reindent(text, config)
        if (!config.applySpacing && !config.applyWrapping) return indented

        // Phase 2: minimal, safe spacing (PSI-free).
        val lines = indented.split('\n')
        val out = StringBuilder(indented.length)
        var inBlockComment = false
        for ((i, rawLine) in lines.withIndex()) {
            var line = rawLine
            if (config.applySpacing) {
                val (parts, nextInBlockComment) = splitIntoParts(rawLine, inBlockComment)
                val sb = StringBuilder()
                for (part in parts) {
                    if (part.type == PartType.Code) {
                        sb.append(applyMinimalSpacingRules(part.text))
                    } else {
                        sb.append(part.text)
                    }
                }
                line = sb.toString()
                inBlockComment = nextInBlockComment
            }
            out.append(line.trimEnd())
            if (i < lines.lastIndex) out.append('\n')
        }
        val spacedText = out.toString()

        // Phase 3: controlled wrapping (only if enabled)
        if (!config.applyWrapping) return spacedText
        return applyControlledWrapping(spacedText, config)
    }

    /**
     * Reindents a slice of [text] specified by [range] and returns a new string with that slice replaced.
     * By default, preserves the base indent of the first line in the slice (so the block stays at
     * the same outer indentation level) while normalizing its inner structure according to the formatter rules.
     */
    fun reindentRange(
        text: String,
        range: IntRange,
        config: LyngFormatConfig = LyngFormatConfig(),
        preserveBaseIndent: Boolean = true,
        baseIndentFrom: Int? = null
    ): String {
        if (range.isEmpty()) return text
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        val slice = text.substring(start, endExclusive)
        val formattedZero = reindent(slice, config)
        val resultSlice = if (!preserveBaseIndent) formattedZero else run {
            // Compute base indent from the beginning of the current line up to [baseStart].
            // If there is any non-whitespace between the line start and [start], we consider it a mid-line paste
            // and do not apply a base indent (to avoid corrupting code). Otherwise, preserve the existing
            // leading whitespace up to the caret as the base for the pasted block.
            val baseStartIndex = (baseIndentFrom ?: start).coerceIn(0, text.length)
            val lineStart = run {
                var i = (baseStartIndex - 1).coerceAtLeast(0)
                while (i >= 0 && text[i] != '\n') i--
                i + 1
            }
            var i = lineStart
            var onlyWs = true
            val base = StringBuilder()
            while (i < baseStartIndex) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t') {
                    base.append(ch)
                } else {
                    onlyWs = false
                    break
                }
                i++
            }
            // Normalize collected base indent: replace tabs with spaces
            var baseIndent = if (onlyWs) {
                if (start == lineStart) {
                    // Range starts at line start, pick up this line's indentation as base
                    var k = start
                    val lineIndent = StringBuilder()
                    while (k < text.length && text[k] != '\n' && (text[k] == ' ' || text[k] == '\t')) {
                        lineIndent.append(text[k])
                        k++
                    }
                    lineIndent.toString().replace("\t", " ".repeat(config.indentSize))
                } else {
                    base.toString().replace("\t", " ".repeat(config.indentSize))
                }
            } else ""
            var parentBaseIndent: String? = baseIndent
            if (baseIndent.isEmpty()) {
                // Fallback: use the indent of the nearest previous non-empty line as base.
                // This helps when pasting at column 0 of a blank line inside a block.
                var j = (lineStart - 2).coerceAtLeast(0)
                // move j to end of previous line
                while (j >= 0 && text[j] != '\n') j--
                // now scan lines backwards to find a non-empty line
                var foundIndent: String? = null
                var prevLineEndsWithOpenBrace = false
                var p = (j - 1).coerceAtLeast(0)
                while (p >= 0) {
                    // find start of this line
                    var ls = p
                    while (ls >= 0 && text[ls] != '\n') ls--
                    val startLine = ls + 1
                    val endLine = p + 1
                    // extract line
                    val lineText = text.subSequence(startLine, endLine)
                    val trimmed = lineText.dropWhile { it == ' ' || it == '\t' }
                    if (trimmed.isNotEmpty()) {
                        // take its leading whitespace as base indent
                        val wsLen = lineText.length - trimmed.length
                        foundIndent = lineText.substring(0, wsLen)
                        // Decide if this line ends with an opening brace before any // comment
                        val codePart = run {
                            val s = lineText.toString()
                            val idx = s.indexOf("//")
                            if (idx >= 0) s.substring(0, idx) else s
                        }
                        prevLineEndsWithOpenBrace = codePart.trimEnd().endsWith("{")
                        break
                    }
                    // move to previous line
                    p = (ls - 1).coerceAtLeast(-1)
                    if (p < 0) break
                }
                if (foundIndent != null) {
                    // If we are right after a line that opens a block, the base for the pasted
                    // content should be one indent unit deeper than that line's base.
                    val normFound = foundIndent.replace("\t", " ".repeat(config.indentSize))
                    parentBaseIndent = normFound
                    baseIndent = if (prevLineEndsWithOpenBrace) {
                        normFound + " ".repeat(config.indentSize.coerceAtLeast(1))
                    } else normFound
                }
                if (baseIndent.isEmpty()) {
                    // Second fallback: compute structural block level up to this line and use it as base.
                    // We scan from start to lineStart and compute '{' '}' balance ignoring // comments.
                    var level = 0
                    var iScan = 0
                    while (iScan < lineStart && iScan < text.length) {
                        // read line
                        var lineEnd = iScan
                        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
                        val raw = text.subSequence(iScan, lineEnd).toString()
                        val code = raw.substringBefore("//")
                        for (ch in code) {
                            when (ch) {
                                '{' -> level++
                                '}' -> if (level > 0) level--
                            }
                        }
                        iScan = if (lineEnd < text.length) lineEnd + 1 else lineEnd
                    }
                    if (level > 0) {
                        parentBaseIndent = " ".repeat((level - 1).coerceAtLeast(0) * config.indentSize.coerceAtLeast(1))
                        baseIndent = " ".repeat(level * config.indentSize.coerceAtLeast(1))
                    }
                }
            }
            if (baseIndent.isEmpty()) formattedZero else {
                val sb = StringBuilder(formattedZero.length + 32)
                var i = 0
                while (i < formattedZero.length) {
                    val lineStart = i
                    var lineEnd = formattedZero.indexOf('\n', lineStart)
                    if (lineEnd < 0) lineEnd = formattedZero.length
                    val line = formattedZero.substring(lineStart, lineEnd)
                    if (line.isNotEmpty()) {
                        // Apply the SAME base indent to all lines in the slice, including '}' lines.
                        // Structural alignment of braces is already handled inside formattedZero.
                        sb.append(baseIndent).append(line)
                    } else sb.append(line)
                    if (lineEnd < formattedZero.length) sb.append('\n')
                    i = lineEnd + 1
                }
                sb.toString()
            }
        }
        if (resultSlice == slice) return text
        val sb = StringBuilder(text.length - slice.length + resultSlice.length)
        sb.append(text, 0, start)
        sb.append(resultSlice)
        sb.append(text, endExclusive, text.length)
        return sb.toString()
    }
}

private enum class PartType { Code, StringLiteral, BlockComment, LineComment }
private data class Part(val text: String, val type: PartType)

/**
 * Split a line into parts: code, string literals, and comments.
 * Tracks [inBlockComment] state across lines.
 */
private fun splitIntoParts(
    text: String,
    inBlockCommentInitial: Boolean
): Pair<List<Part>, Boolean> {
    val result = mutableListOf<Part>()
    var i = 0
    var last = 0
    var inBlockComment = inBlockCommentInitial
    var inString = false
    var quoteChar = ' '

    while (i < text.length) {
        if (inBlockComment) {
            if (text.startsWith("*/", i)) {
                result.add(Part(text.substring(last, i + 2), PartType.BlockComment))
                inBlockComment = false
                i += 2
                last = i
            } else {
                i++
            }
        } else if (inString) {
            if (text[i] == quoteChar) {
                var escapeCount = 0
                var j = i - 1
                while (j >= 0 && text[j] == '\\') {
                    escapeCount++
                    j--
                }
                if (escapeCount % 2 == 0) {
                    inString = false
                    result.add(Part(text.substring(last, i + 1), PartType.StringLiteral))
                    last = i + 1
                }
            }
            i++
        } else {
            if (text.startsWith("//", i)) {
                if (i > last) result.add(Part(text.substring(last, i), PartType.Code))
                result.add(Part(text.substring(i), PartType.LineComment))
                last = text.length
                break
            } else if (text.startsWith("/*", i)) {
                if (i > last) result.add(Part(text.substring(last, i), PartType.Code))
                inBlockComment = true
                last = i
                i += 2
            } else if (text[i] == '"' || text[i] == '\'') {
                if (i > last) result.add(Part(text.substring(last, i), PartType.Code))
                inString = true
                quoteChar = text[i]
                last = i
                i++
            } else {
                i++
            }
        }
    }
    if (last < text.length) {
        val leftover = text.substring(last)
        val type = if (inBlockComment) PartType.BlockComment else PartType.Code
        result.add(Part(leftover, type))
    }
    return result to inBlockComment
}

private fun applyMinimalSpacingRules(code: String): String {
    var s = code
    // Ensure space before '(' for control-flow keywords
    s = s.replace(Regex("\\b(if|for|while|return|break|continue)\\("), "$1 (")
    // Space before '{' for control-flow headers only (avoid function declarations)
    s = s.replace(Regex("\\b(if|for|while)(\\s*\\([^)]*\\))\\s*\\{"), "$1$2 {")
    s = s.replace(Regex("\\belse\\s+if(\\s*\\([^)]*\\))\\s*\\{"), "else if$1 {")
    // Do NOT globally convert "){" to ") {"; this would break function declarations.
    // Normalize control-flow braces explicitly:
    s = s.replace(Regex("\\bif\\s*\\(([^)]*)\\)\\s*\\{"), "if ($1) {")
    s = s.replace(Regex("\\bfor\\s*\\(([^)]*)\\)\\s*\\{"), "for ($1) {")
    s = s.replace(Regex("\\bwhile\\s*\\(([^)]*)\\)\\s*\\{"), "while ($1) {")
    s = s.replace(Regex("\\belse\\s+if\\s*\\(([^)]*)\\)\\s*\\{"), "else if ($1) {")
    s = s.replace(Regex("\\belse\\{"), "else {")
    // Ensure space between closing brace and else
    s = s.replace(Regex("\\}\\s*else\\b"), "} else")
    // Ensure single space in "else if"
    s = s.replace(Regex("\\belse\\s+if\\b"), "else if")
    // Ensure space before '{' for try/catch/finally
    s = s.replace(Regex("\\b(try|catch|finally)\\{"), "$1 {")
    // Ensure space before '{' when catch has parameters: "catch (e){" -> "catch (e) {"
    s = s.replace(Regex("(\\bcatch\\s*\\([^)]*\\))\\s*\\{"), "$1 {")
    // Ensure space before '(' for catch parameter
    s = s.replace(Regex("\\bcatch\\("), "catch (")
    // Remove space between control keyword and label: return @label -> return@label
    s = s.replace(Regex("\\b(return|break|continue)\\s+(@[\\p{L}_][\\p{L}\\p{N}_]*)"), "$1$2")
    // Remove spaces just inside parentheses/brackets: "( a )" -> "(a)"
    s = s.replace(Regex("\\(\\s+"), "(")
    // Do not strip leading indentation before a closing bracket/paren on its own line
    s = s.replace(Regex("(?<=\\S)\\s+\\)"), ")")
    s = s.replace(Regex("\\[\\s+"), "[")
    s = s.replace(Regex("(?<=\\S)\\s+\\]"), "]")
    // Keep function declarations as-is; don't force or remove space before '{' in function headers.
    // Commas: no space before, one space after (unless followed by ) or ])
    // Comma spacing: ensure one space after, none before; but avoid trailing spaces at EOL
    s = s.replace(Regex("\\s*,\\s*"), ", ")
    // Remove space after comma if it ends the line or before a closing paren/bracket
    s = s.replace(Regex(", \\r?\\n"), ",\n")
    s = s.replace(Regex(", \\)"), ",)")
    s = s.replace(Regex(", \\]"), ",]")
    // Equality/boolean operators: ensure spaces around
    s = s.replace(Regex("\\s*(==|!=|<=|>=|&&|\\|\\||\\+=|-=|\\*=|/=|=>|->)\\s*"), " $1 ")
    // Assignment '=' (not part of '==', '!=', '<=', '>=', '=>', '+=', '-=', '*=', '/=', '->'): collapse whitespace to single spaces around '='
    s = s.replace(Regex("(?<![=!<>+\\-*/])\\s*=\\s*(?![=>])"), " = ")
    // Multiply/Divide/Mod as binary: spaces around. Avoid '*=', '/='
    s = s.replace(Regex("(?<![*])\\s*([*/%])\\s*(?![=])"), " $1 ")
    // Addition as binary: spaces around '+'. We try not to break '++' and '+=' here.
    s = s.replace(Regex("(?<![+])\\s*\\+\\s*(?![+=])"), " + ")
    // Subtraction as binary: add spaces when it looks binary. Keep unary '-' tight (after start or ( [ { = : ,)
    // and avoid splitting '-=', '--', '->'
    s = s.replace(Regex("(?<=[^\\s(\\[\\{=:,])\\s*-\\s*(?=[^=\\->])"), " - ")
    // Colon in types/extends: remove spaces before, ensure one space after (keep '::' intact)
    s = s.replace(Regex("(?<!:)\\s*:(?!:)\\s*"), ": ")
    return s
}

private fun applyControlledWrapping(text: String, cfg: LyngFormatConfig): String {
    if (!cfg.applyWrapping) return text
    val lines = text.split('\n')
    val out = StringBuilder(text.length)
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.length > cfg.maxLineLength && line.contains('(') && line.contains(',') && !line.contains('"') && !line.contains('\'')) {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            if (open in 0 until close) {
                val head = line.substring(0, open + 1)
                val args = line.substring(open + 1, close)
                val tail = line.substring(close)
                // Split by commas without adding trailing comma
                val parts = args.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val baseIndent = leadingWhitespaceOf(head)
                    val contIndent = baseIndent + if (cfg.useTabs) "\t" else " ".repeat(cfg.continuationIndentSize)
                    out.append(head.trimEnd()).append('\n')
                    for ((idx, p) in parts.withIndex()) {
                        out.append(contIndent).append(p)
                        if (idx < parts.lastIndex) out.append(',')
                        out.append('\n')
                    }
                    out.append(baseIndent).append(tail.trimStart())
                    out.append('\n')
                    i++
                    continue
                }
            }
        }
        out.append(line)
        if (i < lines.lastIndex) out.append('\n')
        i++
    }
    return out.toString()
}

private fun leadingWhitespaceOf(s: String): String {
    var k = 0
    while (k < s.length && (s[k] == ' ' || s[k] == '\t')) k++
    return s.substring(0, k)
}
