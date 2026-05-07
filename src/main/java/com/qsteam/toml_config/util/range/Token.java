package com.qsteam.toml_config.util.range;

final class Token {
    enum Type {
        L_PAREN,
        R_PAREN,
        L_BRACKET,
        R_BRACKET,
        L_BRACE,
        R_BRACE,
        COMMA,
        BANG,
        AND,
        OR,
        DOT_DOT,
        IDENT,
        EOF
    }

    final Type type;
    final String text;

    Token(Type type, String text) {
        this.type = type;
        this.text = text;
    }
}

