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
package net.sergeych.lyng.format

/**
 * Lightweight, PSI‑free formatter for Lyng source code.
 *
 * Phase 1 focuses on indentation from scratch (idempotent). Spacing/wrapping may be
 * extended later based on [LyngFormatConfig] flags.
 */
object LyngFormatter {

    /** Returns the input with indentation recomputed from scratch, line by line. */
    fun reindent(text: String, config: LyngFormatConfig = LyngFormatConfig()): String {
        val lines = text.split('\n')
        val sb = StringBuilder(text.length + lines.size)
        var blockLevel = 0
        var parenBalance = 0
        var bracketBalance = 0
        var prevBracketContinuation = false
        val bracketBaseStack = ArrayDeque<String>()

        fun codePart(s: String): String {
            val idx = s.indexOf("//")
            return if (idx >= 0) s.substring(0, idx) else s
        }

        fun indentOf(level: Int, continuation: Int): String =
            if (config.useTabs) "\t".repeat(level) + " ".repeat(continuation)
            else " ".repeat(level * config.indentSize + continuation)

        var awaitingSingleIndent = false
        fun isControlHeaderNoBrace(s: String): Boolean {
            val t = s.trim()
            if (t.isEmpty()) return false
            // match: if (...) | else if (...) | else
            val isIf = Regex("^if\\s*\\(.*\\)\\s*$").matches(t)
            val isElseIf = Regex("^else\\s+if\\s*\\(.*\\)\\s*$").matches(t)
            val isElse = t == "else"
            return isIf || isElseIf || isElse
        }

        for ((i, rawLine) in lines.withIndex()) {
            val line = rawLine
            val code = codePart(line)
            val trimmedStart = code.dropWhile { it == ' ' || it == '\t' }

            // Compute effective indent level for this line
            var effectiveLevel = blockLevel
            if (trimmedStart.startsWith("}")) effectiveLevel = (effectiveLevel - 1).coerceAtLeast(0)
            // else/catch/finally should align with the parent block level; no extra dedent here,
            // because the preceding '}' has already reduced [blockLevel] appropriately.

            // Single-line control header (if/else/else if) without braces: indent the next
            // non-empty, non-'}', non-'else' line by one extra level
            val applyAwaiting = awaitingSingleIndent && trimmedStart.isNotEmpty() &&
                    !trimmedStart.startsWith("else") && !trimmedStart.startsWith("}")
            if (applyAwaiting) effectiveLevel += 1

            val firstChar = trimmedStart.firstOrNull()
            // Do not apply continuation on a line that starts with a closer ')' or ']'
            val startsWithCloser = firstChar == ')' || firstChar == ']'
            // Kotlin-like rule: continuation persists while inside parentheses; it applies
            // even on the line that starts with ')'. For brackets, do not apply on the ']' line itself.
            // Continuation rules:
            // - For brackets: one-shot continuation for first element after '[', and while inside brackets
            //   (except on the ']' line) continuation equals one unit.
            // - For parentheses: continuation depth scales with nested level; e.g., inside two nested
            //   parentheses lines get 2 * continuationIndentSize. No continuation on a line that starts with ')'.
            val parenContLevels = if (parenBalance > 0 && firstChar != ')') parenBalance else 0
            val continuation = when {
                // One-shot continuation when previous line ended with '[' to align first element
                prevBracketContinuation && firstChar != ']' -> config.continuationIndentSize
                // While inside brackets, continuation applies (single unit) except on the closing line
                bracketBalance > 0 && firstChar != ']' -> config.continuationIndentSize
                // While inside parentheses, continuation applies scaled by nesting level
                parenContLevels > 0 -> config.continuationIndentSize * parenContLevels
                else -> 0
            }

            // Replace leading whitespace with the exact target indent; but keep fully blank lines truly empty
            val contentStart = line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it }
            var content = line.substring(contentStart)
            // Collapse spaces right after an opening '[' to avoid "[    1"; make it "[1"
            if (content.startsWith("[")) {
                content = "[" + content.drop(1).trimStart()
            }
            // Determine base indent: for bracket blocks, preserve the exact leading whitespace
            val leadingWs = if (contentStart > 0) line.substring(0, contentStart) else ""
            val currentBracketBase = if (bracketBaseStack.isNotEmpty()) bracketBaseStack.last() else null
            val indentString = if (currentBracketBase != null) {
                val cont = if (continuation > 0) {
                    if (config.useTabs) "\t" else " ".repeat(continuation)
                } else ""
                currentBracketBase + cont
            } else indentOf(effectiveLevel, continuation)
            if (content.isEmpty()) {
                // preserve truly blank line as empty to avoid trailing spaces on empty lines
                // (also keeps continuation blocks visually clean)
                // do nothing, just append nothing; newline will be appended below if needed
            } else {
                sb.append(indentString).append(content)
            }

            // New line (keep EOF semantics similar to input)
            if (i < lines.lastIndex) sb.append('\n')

            // Update balances using this line's code content
            for (ch in code) when (ch) {
                '{' -> blockLevel++
                '}' -> if (blockLevel > 0) blockLevel--
                '(' -> parenBalance++
                ')' -> if (parenBalance > 0) parenBalance--
                '[' -> bracketBalance++
                ']' -> if (bracketBalance > 0) bracketBalance--
            }

            // Update awaitingSingleIndent based on current line
            if (applyAwaiting && trimmedStart.isNotEmpty()) {
                // we have just consumed the body line
                awaitingSingleIndent = false
            } else {
                // start awaiting if current line is a control header without '{'
                val endsWithBrace = code.trimEnd().endsWith("{")
                if (!endsWithBrace && isControlHeaderNoBrace(code)) {
                    awaitingSingleIndent = true
                }
            }

            // Prepare one-shot bracket continuation if the current line ends with '['
            // (first element line gets continuation even before balances update propagate).
            val endsWithBracket = code.trimEnd().endsWith("[")
            // Reset one-shot flag after we used it on this line
            if (prevBracketContinuation) prevBracketContinuation = false
            // Set for the next iteration if current line ends with '['
            if (endsWithBracket) {
                prevBracketContinuation = true
                // Push base indent of the '[' line for subsequent lines in this bracket block
                bracketBaseStack.addLast(leadingWs)
            }

            // If this line starts with ']' (closing bracket), pop the preserved base for this bracket level
            if (trimmedStart.startsWith("]") && bracketBaseStack.isNotEmpty()) {
                // ensure stack stays in sync with bracket levels
                bracketBaseStack.removeLast()
            }
        }
        return sb.toString()
    }

    /** Full format. Currently performs indentation only; spacing/wrapping can be added later. */
    fun format(text: String, config: LyngFormatConfig = LyngFormatConfig()): String {
        // Phase 1: indentation
        val indented = reindent(text, config)
        if (!config.applySpacing && !config.applyWrapping) return indented

        // Phase 2: minimal, safe spacing (PSI-free). Skip block comments completely and
        // only apply spacing to the part before '//' on each line.
        val lines = indented.split('\n')
        val out = StringBuilder(indented.length)
        var inBlockComment = false
        for ((i, rawLine) in lines.withIndex()) {
            var line = rawLine
            if (config.applySpacing) {
                if (inBlockComment) {
                    // Pass-through until we see the end of the block comment on some line
                    val end = line.indexOf("*/")
                    if (end >= 0) {
                        inBlockComment = false
                    }
                } else {
                    // If this line opens a block comment, apply spacing only before the opener
                    val startIdx = line.indexOf("/*")
                    val endIdx = line.indexOf("*/")
                    if (startIdx >= 0 && (endIdx < 0 || endIdx < startIdx)) {
                        val before = line.substring(0, startIdx)
                        val after = line.substring(startIdx)
                        val commentIdx = before.indexOf("//")
                        val code = if (commentIdx >= 0) before.substring(0, commentIdx) else before
                        val tail = if (commentIdx >= 0) before.substring(commentIdx) else ""
                        val spaced = applyMinimalSpacing(code)
                        line = (spaced + tail) + after
                        inBlockComment = true
                    } else {
                        // Normal code line: respect single-line comments
                        val commentIdx = line.indexOf("//")
                        if (commentIdx >= 0) {
                            val code = line.substring(0, commentIdx)
                            val tail = line.substring(commentIdx)
                            val spaced = applyMinimalSpacing(code)
                            line = spaced + tail
                        } else {
                            line = applyMinimalSpacing(line)
                        }
                    }
                }
            }
            out.append(line.trimEnd())
            if (i < lines.lastIndex) out.append('\n')
        }
        val spacedText = out.toString()

        // Phase 3: controlled wrapping (only if enabled)
        if (!config.applyWrapping) return spacedText
        return applyControlledWrapping(spacedText, config)
    }

    private fun startsWithWord(s: String, w: String): Boolean =
        s.startsWith(w) && s.getOrNull(w.length)?.let { !it.isLetterOrDigit() && it != '_' } != false

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
            var baseIndent = if (onlyWs) base.toString() else ""
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
                    parentBaseIndent = foundIndent
                    baseIndent = if (prevLineEndsWithOpenBrace) {
                        if (config.useTabs) foundIndent + "\t" else foundIndent + " ".repeat(config.indentSize.coerceAtLeast(1))
                    } else foundIndent
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
                        parentBaseIndent = if (config.useTabs) "\t".repeat(level - 1) else " ".repeat((level - 1).coerceAtLeast(0) * config.indentSize.coerceAtLeast(1))
                        baseIndent = if (config.useTabs) "\t".repeat(level) else " ".repeat(level * config.indentSize.coerceAtLeast(1))
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
                        val isCloser = line.dropWhile { it == ' ' || it == '\t' }.startsWith("}")
                        val indentToUse = if (isCloser && parentBaseIndent != null) parentBaseIndent!! else baseIndent
                        sb.append(indentToUse).append(line)
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

private fun applyMinimalSpacing(code: String): String {
    var s = code
    // Ensure space before '(' for control-flow keywords
    s = s.replace(Regex("\\b(if|for|while)\\("), "$1 (")
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
    s = s.replace(Regex("\\s*(==|!=|<=|>=|&&|\\|\\|)\\s*"), " $1 ")
    // Assignment '=' (not part of '==', '!=', '<=', '>=', '=>'): collapse whitespace to single spaces around '='
    s = s.replace(Regex("(?<![=!<>])\\s*=\\s*(?![=>])"), " = ")
    // Multiply/Divide/Mod as binary: spaces around
    s = s.replace(Regex("\\s*([*/%])\\s*"), " $1 ")
    // Addition as binary: spaces around '+'. We try not to break '++' and '+=' here.
    s = s.replace(Regex("(?<![+])\\s*\\+\\s*(?![+=])"), " + ")
    // Subtraction as binary: add spaces when it looks binary. Keep unary '-' tight (after start or ( [ { = : ,)
    s = s.replace(Regex("(?<=[^\\s(\\[\\{=:,])-(?=[^=])"), " - ")
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
