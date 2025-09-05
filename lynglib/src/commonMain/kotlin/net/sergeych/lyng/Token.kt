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

data class Token(val value: String, val pos: Pos, val type: Type) {
    fun raiseSyntax(text: String): Nothing {
        throw ScriptError(pos, text)
    }

    val isComment: Boolean by lazy { type == Type.SINLGE_LINE_COMMENT || type == Type.MULTILINE_COMMENT }

    fun isId(text: String) =
        type == Type.ID && value == text


    @Suppress("unused")
    enum class Type {
        ID, INT, REAL, HEX, STRING, CHAR,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        SEMICOLON, COLON,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        ASSIGN, PLUSASSIGN, MINUSASSIGN, STARASSIGN, SLASHASSIGN, PERCENTASSIGN,
        PLUS2, MINUS2,
        IN, NOTIN, IS, NOTIS,
        EQ, NEQ, LT, LTE, GT, GTE, REF_EQ, REF_NEQ, MATCH, NOTMATCH,
        SHUTTLE,
        AND, BITAND, OR, BITOR, NOT, BITNOT, DOT, ARROW, EQARROW, QUESTION, COLONCOLON,
        SINLGE_LINE_COMMENT, MULTILINE_COMMENT,
        LABEL, ATLABEL, // label@ at@label
        //PUBLIC, PROTECTED, INTERNAL, EXPORT, OPEN, INLINE, OVERRIDE, ABSTRACT, SEALED, EXTERNAL, VAL, VAR, CONST, TYPE, FUN, CLASS, INTERFACE, ENUM, OBJECT, TRAIT, THIS,
        ELLIPSIS, DOTDOT, DOTDOTLT,
        NEWLINE,
        EOF,
        NULL_COALESCE,
        ELVIS,
        NULL_COALESCE_INDEX,
        NULL_COALESCE_INVOKE,
        NULL_COALESCE_BLOCKINVOKE,
    }

    companion object {
//        fun eof(parser: Parser) = Token("", parser.currentPos, Type.EOF)
    }
}