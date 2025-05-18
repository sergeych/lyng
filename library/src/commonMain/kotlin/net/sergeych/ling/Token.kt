package net.sergeych.ling

data class Token(val value: String, val pos: Pos, val type: Type) {
    enum class Type {
        ID, INT, REAL, HEX, STRING, LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        SEMICOLON, COLON, EQ, PLUS, MINUS, STAR, SLASH, ASSIGN, EQEQ, NEQ, LT, LTEQ, GT,
        GTEQ, AND, BITAND, OR, BITOR, NOT, DOT, ARROW, QUESTION, COLONCOLON, PERCENT,
        EOF,
    }
    companion object {
//        fun eof(parser: Parser) = Token("", parser.currentPos, Type.EOF)
    }
}