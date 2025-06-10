package net.sergeych.lyng

data class Token(val value: String, val pos: Pos, val type: Type) {
    val isComment: Boolean by lazy { type == Type.SINLGE_LINE_COMMENT || type == Type.MULTILINE_COMMENT }

    @Suppress("unused")
    enum class Type {
        ID, INT, REAL, HEX, STRING, CHAR,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        SEMICOLON, COLON,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        ASSIGN, PLUSASSIGN, MINUSASSIGN, STARASSIGN, SLASHASSIGN, PERCENTASSIGN,
        PLUS2, MINUS2,
        IN, NOTIN, IS, NOTIS,
        EQ, NEQ, LT, LTE, GT, GTE, REF_EQ, REF_NEQ,
        SHUTTLE,
        AND, BITAND, OR, BITOR, NOT, BITNOT, DOT, ARROW, QUESTION, COLONCOLON,
        SINLGE_LINE_COMMENT, MULTILINE_COMMENT,
        LABEL, ATLABEL, // label@ at@label
        PRIVATE, PROTECTED,
        //PUBLIC, PROTECTED, INTERNAL, EXPORT, OPEN, INLINE, OVERRIDE, ABSTRACT, SEALED, EXTERNAL, VAL, VAR, CONST, TYPE, FUN, CLASS, INTERFACE, ENUM, OBJECT, TRAIT, THIS,
        ELLIPSIS, DOTDOT, DOTDOTLT,
        NEWLINE,
        EOF,
    }

    companion object {
//        fun eof(parser: Parser) = Token("", parser.currentPos, Type.EOF)
    }
}