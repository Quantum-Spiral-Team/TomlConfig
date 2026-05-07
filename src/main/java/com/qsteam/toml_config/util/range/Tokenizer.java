package com.qsteam.toml_config.util.range;

import java.util.ArrayList;
import java.util.List;

import static com.qsteam.toml_config.util.range.Token.Type.*;

final class Tokenizer {

    static List<Token> tokenize(String input) {
        String s = input == null ? "" : input.trim();
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '(') {
                tokens.add(new Token(L_PAREN, "("));
                i++;
                continue;
            }
            if (c == ')') {
                tokens.add(new Token(R_PAREN, ")"));
                i++;
                continue;
            }
            if (c == '[') {
                tokens.add(new Token(L_BRACKET, "["));
                i++;
                continue;
            }
            if (c == ']') {
                tokens.add(new Token(R_BRACKET, "]"));
                i++;
                continue;
            }
            if (c == '{') {
                tokens.add(new Token(L_BRACE, "{"));
                i++;
                continue;
            }
            if (c == '}') {
                tokens.add(new Token(R_BRACE, "}"));
                i++;
                continue;
            }
            if (c == ',') {
                tokens.add(new Token(COMMA, ","));
                i++;
                continue;
            }
            if (c == '!') {
                tokens.add(new Token(BANG, "!"));
                i++;
                continue;
            }
            if (c == '&') {
                tokens.add(new Token(AND, "&"));
                i++;
                continue;
            }
            if (c == '|') {
                tokens.add(new Token(OR, "|"));
                i++;
                continue;
            }
            if (c == '.' && i + 1 < s.length() && s.charAt(i + 1) == '.') {
                tokens.add(new Token(DOT_DOT, ".."));
                i += 2;
                continue;
            }

            int start = i;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) break;
                if (ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}' ||
                        ch == ',' || ch == '!' || ch == '&' || ch == '|' ) {
                    break;
                }
                if (ch == '.' && i + 1 < s.length() && s.charAt(i + 1) == '.') {
                    break;
                }
                i++;
            }
            if (start == i) {
                // unknown char, skip
                i++;
                continue;
            }
            tokens.add(new Token(IDENT, s.substring(start, i)));
        }
        tokens.add(new Token(EOF, ""));
        return tokens;
    }
}

