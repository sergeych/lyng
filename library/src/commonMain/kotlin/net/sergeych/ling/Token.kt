package net.sergeych.ling

data class Token(val value: String, val pos: Pos, val type: Type) {
    @Suppress("unused")
    enum class Type {
        ID, INT, REAL, HEX, STRING, LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        SEMICOLON, COLON,
        PLUS, MINUS, STAR, SLASH, ASSIGN,
        EQ, NEQ, LT, LTE, GT, GTE,
        AND, BITAND, OR, BITOR, NOT, DOT, ARROW, QUESTION, COLONCOLON, PERCENT,
        SINLGE_LINE_COMMENT, MULTILINE_COMMENT,
        EOF,
    }

    companion object {
//        fun eof(parser: Parser) = Token("", parser.currentPos, Type.EOF)
    }
}