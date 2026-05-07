package com.qsteam.toml_config.util.range;

import com.qsteam.toml_config.api.ConfigValue;

import java.util.*;

import static com.qsteam.toml_config.util.range.Token.Type.*;

/**
 * Parser for the Range-DSL used by {@link ConfigValue#range()}.
 * <p>
 * Grammar summary:
 * <ul>
 *   <li>Unary: {@code !expr}</li>
 *   <li>Binary: {@code expr & expr}, {@code expr | expr} with precedence {@code ! > & > |}</li>
 *   <li>Grouping: {@code (expr)}</li>
 *   <li>Set literal: {@code {a,b,c}}</li>
 *   <li>Interval (numeric only): {@code [a..b]}, {@code (a..b]}, {@code [a..b)}, {@code (a..b)}</li>
 * </ul>
 * <p>
 * Note: {@code (...)} is ambiguous (group vs interval). This parser treats {@code (...)} as an interval
 * only when it contains the {@code ..} token in the interval position, otherwise it's treated as a group.
 */
public final class RangeParser {

    public enum ValueKind {
        NUMBER,
        DISCRETE
    }

    private final List<Token> tokens;
    private int pos = 0;
    private final ValueKind kind;

    private RangeParser(String input, ValueKind kind) {
        this.tokens = Tokenizer.tokenize(input);
        this.kind = kind;
    }

    public static RangeExpression<Object> parse(String input, ValueKind kind) {
        RangeParser parser = new RangeParser(input, kind);
        RangeExpression<Object> expr = parser.parseOr();
        parser.expect(EOF);
        return expr;
    }

    private RangeExpression<Object> parseOr() {
        RangeExpression<Object> left = parseAnd();
        while (match(OR)) {
            RangeExpression<Object> right = parseAnd();
            left = new OrExpr(left, right);
        }
        return left;
    }

    private RangeExpression<Object> parseAnd() {
        RangeExpression<Object> left = parseUnary();
        while (match(AND)) {
            RangeExpression<Object> right = parseUnary();
            left = new AndExpr(left, right);
        }
        return left;
    }

    private RangeExpression<Object> parseUnary() {
        if (match(BANG)) {
            return new NotExpr(parseUnary());
        }
        return parsePrimary();
    }

    private RangeExpression<Object> parsePrimary() {
        Token t = peek();
        if (t.type == L_BRACE) {
            return parseSetLiteral();
        }
        if (t.type == L_BRACKET) {
            if (kind != ValueKind.NUMBER) {
                throw new ParseException("Intervals are only supported for numeric types");
            }
            return parseIntervalLiteral(L_BRACKET);
        }
        if (t.type == L_PAREN) {
            // Ambiguous: interval or group. Rule: if inside starts with '..' token, treat as interval.
            // More generally: if we can parse as interval (expects DOT_DOT), do it, else group.
            if (kind == ValueKind.NUMBER && looksLikeInterval()) {
                return parseIntervalLiteral(L_PAREN);
            }
            consume(L_PAREN);
            RangeExpression<Object> inner = parseOr();
            consume(R_PAREN);
            return inner;
        }

        throw new ParseException("Unexpected token: " + t.type);
    }

    private boolean looksLikeInterval() {
        int start = pos;
        if (tokens.get(start).type != L_PAREN) {
            return false;
        }
        int i = start + 1;
        // optional bound
        if (tokens.get(i).type == IDENT) {
            i++;
        }
        // must have DOT_DOT next
        return tokens.get(i).type == DOT_DOT;
    }

    private RangeExpression<Object> parseSetLiteral() {
        consume(L_BRACE);
        Set<String> items = new LinkedHashSet<>();
        if (!check(R_BRACE)) {
            do {
                Token value = consume(IDENT);
                items.add(value.text.trim());
            } while (match(COMMA));
        }
        consume(R_BRACE);
        return kind == ValueKind.NUMBER ? new NumberSetExpr(items) : new DiscreteSetExpr(items);
    }

    private RangeExpression<Object> parseIntervalLiteral(Token.Type opening) {
        boolean includeLower = opening == L_BRACKET;
        boolean includeUpper;

        consume(opening);
        Token lowerTok = null;
        if (check(IDENT)) {
            lowerTok = consume(IDENT);
        }
        consume(DOT_DOT);
        Token upperTok = null;
        if (check(IDENT)) {
            upperTok = consume(IDENT);
        }

        Token.Type closingType = opening == L_BRACKET ? R_BRACKET : R_PAREN;
        if (opening == L_PAREN) {
            // could also be (a..b] or (a..b) etc; accept ] or )
            if (check(R_BRACKET)) {
                closingType = R_BRACKET;
            } else if (check(R_PAREN)) {
                closingType = R_PAREN;
            } else {
                throw new ParseException("Expected closing bracket/paren for interval");
            }
        } else if (opening == L_BRACKET) {
            // could also be [a..b) or [a..b]
            if (check(R_PAREN)) {
                closingType = R_PAREN;
            } else if (check(R_BRACKET)) {
                closingType = R_BRACKET;
            } else {
                throw new ParseException("Expected closing bracket/paren for interval");
            }
        }

        includeUpper = closingType == R_BRACKET;
        consume(closingType);

        Double lower = lowerTok != null ? parseNumber(lowerTok.text) : null;
        Double upper = upperTok != null ? parseNumber(upperTok.text) : null;

        // Infinity rule: if bound is missing, bracket must be round
        if (lower == null && includeLower) {
            throw new ParseException("Unbounded lower interval must use '('");
        }
        if (upper == null && includeUpper) {
            throw new ParseException("Unbounded upper interval must use ')'");
        }

        return new IntervalExpr(lower, includeLower, upper, includeUpper);
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid number: " + text);
        }
    }

    private boolean match(Token.Type type) {
        if (check(type)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean check(Token.Type type) {
        return peek().type == type;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token consume(Token.Type type) {
        if (!check(type)) {
            throw new ParseException("Expected " + type + " but found " + peek().type);
        }
        return tokens.get(pos++);
    }

    private Token expect(Token.Type type) {
        return consume(type);
    }

    private void consume(Token.Type... any) {
        Token.Type t = peek().type;
        for (Token.Type a : any) {
            if (t == a) {
                pos++;
                return;
            }
        }
        throw new ParseException("Unexpected token: " + t);
    }

    // ===== AST nodes =====

    private static final class AndExpr implements RangeExpression<Object>, NumberDomain {
        private final RangeExpression<Object> left;
        private final RangeExpression<Object> right;
        private final RangeSet numberDomain;

        private AndExpr(RangeExpression<Object> left, RangeExpression<Object> right) {
            this.left = left;
            this.right = right;
            this.numberDomain = computeNumberDomain();
        }

        @Override
        public boolean matches(Object value) {
            return left.matches(value) && right.matches(value);
        }

        @Override
        public boolean isSatisfiable() {
            if (numberDomain != null) {
                return numberDomain.isNonEmpty();
            }
            return satisfiableDiscrete(left, right, true);
        }

        @Override
        public Set<String> mentionedLiterals() {
            Set<String> s = new LinkedHashSet<>();
            s.addAll(left.mentionedLiterals());
            s.addAll(right.mentionedLiterals());
            return s;
        }

        @Override
        public RangeSet domain() {
            return numberDomain;
        }

        private RangeSet computeNumberDomain() {
            if (left instanceof NumberDomain && right instanceof NumberDomain) {
                RangeSet leftDomain = ((NumberDomain) left).domain();
                RangeSet rightDomain = ((NumberDomain) right).domain();
                if (leftDomain == null || rightDomain == null) {
                    return null;
                }
                return leftDomain.intersect(rightDomain);
            }
            return null;
        }
    }

    private static final class OrExpr implements RangeExpression<Object>, NumberDomain {
        private final RangeExpression<Object> left;
        private final RangeExpression<Object> right;
        private final RangeSet numberDomain;

        private OrExpr(RangeExpression<Object> left, RangeExpression<Object> right) {
            this.left = left;
            this.right = right;
            this.numberDomain = computeNumberDomain();
        }

        @Override
        public boolean matches(Object value) {
            return left.matches(value) || right.matches(value);
        }

        @Override
        public boolean isSatisfiable() {
            if (numberDomain != null) {
                return numberDomain.isNonEmpty();
            }
            return satisfiableDiscrete(left, right, false);
        }

        @Override
        public Set<String> mentionedLiterals() {
            Set<String> s = new LinkedHashSet<>();
            s.addAll(left.mentionedLiterals());
            s.addAll(right.mentionedLiterals());
            return s;
        }

        @Override
        public RangeSet domain() {
            return numberDomain;
        }

        private RangeSet computeNumberDomain() {
            if (left instanceof NumberDomain && right instanceof NumberDomain) {
                RangeSet leftDomain = ((NumberDomain) left).domain();
                RangeSet rightDomain = ((NumberDomain) right).domain();
                if (leftDomain == null || rightDomain == null) {
                    return null;
                }
                return leftDomain.union(rightDomain);
            }
            return null;
        }
    }

    private static final class NotExpr implements RangeExpression<Object>, NumberDomain {
        private final RangeExpression<Object> inner;
        private final RangeSet numberDomain;

        private NotExpr(RangeExpression<Object> inner) {
            this.inner = inner;
            this.numberDomain = computeNumberDomain();
        }

        @Override
        public boolean matches(Object value) {
            return !inner.matches(value);
        }

        @Override
        public boolean isSatisfiable() {
            if (numberDomain != null) {
                return numberDomain.isNonEmpty();
            }
            // If inner matches OTHER and all mentioned values, then complement is unsat.
            Set<String> mentioned = inner.mentionedLiterals();
            for (String m : mentioned) {
                if (!matches(m)) {
                    return true;
                }
            }
            return matches(DiscreteOther.INSTANCE);
        }

        @Override
        public Set<String> mentionedLiterals() {
            return inner.mentionedLiterals();
        }

        @Override
        public RangeSet domain() {
            return numberDomain;
        }

        private RangeSet computeNumberDomain() {
            if (inner instanceof NumberDomain) {
                RangeSet innerDomain = ((NumberDomain) inner).domain();
                if (innerDomain == null) {
                    return null;
                }
                return innerDomain.complement();
            }
            return null;
        }
    }

    private interface NumberDomain {
        RangeSet domain();
    }

    private static final class IntervalExpr implements RangeExpression<Object>, NumberDomain {
        private final Double lower;
        private final boolean includeLower;
        private final Double upper;
        private final boolean includeUpper;

        private IntervalExpr(Double lower, boolean includeLower, Double upper, boolean includeUpper) {
            this.lower = lower;
            this.includeLower = includeLower;
            this.upper = upper;
            this.includeUpper = includeUpper;
        }

        @Override
        public boolean matches(Object value) {
            if (!(value instanceof Number)) return false;
            double v = ((Number) value).doubleValue();
            if (lower != null) {
                if (includeLower) {
                    if (v < lower) return false;
                } else {
                    if (v <= lower) return false;
                }
            }
            if (upper != null) {
                if (includeUpper) {
                    return v <= upper;
                } else {
                    return v < upper;
                }
            }
            return true;
        }

        @Override
        public boolean isSatisfiable() {
            return domain().isNonEmpty();
        }

        @Override
        public Set<String> mentionedLiterals() {
            return Collections.emptySet();
        }

        @Override
        public RangeSet domain() {
            return RangeSet.fromInterval(lower, includeLower, upper, includeUpper);
        }
    }

    private static final class NumberSetExpr implements RangeExpression<Object>, NumberDomain {
        private final Set<String> raw;

        private NumberSetExpr(Set<String> raw) {
            this.raw = raw;
        }

        @Override
        public boolean matches(Object value) {
            if (!(value instanceof Number)) return false;
            double v = ((Number) value).doubleValue();
            for (String r : raw) {
                if (Double.compare(v, parseNumber(r)) == 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isSatisfiable() {
            return domain().isNonEmpty();
        }

        @Override
        public Set<String> mentionedLiterals() {
            return raw;
        }

        @Override
        public RangeSet domain() {
            RangeSet out = RangeSet.empty();
            for (String r : raw) {
                double v = parseNumber(r);
                out = out.union(RangeSet.fromInterval(v, true, v, true));
            }
            return out;
        }
    }

    private static final class DiscreteSetExpr implements RangeExpression<Object> {
        private final Set<String> values;

        private DiscreteSetExpr(Set<String> values) {
            this.values = values;
        }

        @Override
        public boolean matches(Object value) {
            if (value == DiscreteOther.INSTANCE) {
                return false;
            }
            String s = String.valueOf(value).trim();
            for (String v : values) {
                if (v.equalsIgnoreCase(s)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isSatisfiable() {
            return !values.isEmpty();
        }

        @Override
        public Set<String> mentionedLiterals() {
            return values;
        }
    }

    private enum DiscreteOther {
        INSTANCE
    }

    private static boolean satisfiableDiscrete(RangeExpression<Object> left, RangeExpression<Object> right, boolean isAnd) {
        Set<String> mentioned = new LinkedHashSet<>();
        mentioned.addAll(left.mentionedLiterals());
        mentioned.addAll(right.mentionedLiterals());

        for (String m : mentioned) {
            boolean lv = left.matches(m);
            boolean rv = right.matches(m);
            if (isAnd) {
                if (lv && rv) return true;
            } else {
                if (lv || rv) return true;
            }
        }

        boolean lOther = left.matches(DiscreteOther.INSTANCE);
        boolean rOther = right.matches(DiscreteOther.INSTANCE);
        return isAnd ? (lOther && rOther) : (lOther || rOther);
    }

}

