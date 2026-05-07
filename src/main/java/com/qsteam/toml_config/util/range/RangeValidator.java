package com.qsteam.toml_config.util.range;

import com.qsteam.toml_config.TOMLConfigMod;
import com.qsteam.toml_config.api.ConfigValue;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime validation for {@link ConfigValue#range()}.
 * <p>
 * Supports the boolean Range-DSL (NOT/AND/OR) over:
 * <ul>
 *   <li>numeric intervals and numeric set literals</li>
 *   <li>discrete set literals for enums/UUID/strings</li>
 * </ul>
 * <p>
 * If a range expression is invalid or unsatisfiable, restrictions are ignored and an error is logged.
 * Identical parse/unsatisfiable errors are deduplicated to avoid noisy logs.
 * For arrays and iterables the expression is applied per element, using the effective element type.
 */
public final class RangeValidator {

    private static final Logger LOGGER = TOMLConfigMod.getLogger(RangeValidator.class.getSimpleName());
    private static final Set<String> INVALID_RANGE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNSAT_RANGE_LOGGED = ConcurrentHashMap.newKeySet();

    private RangeValidator() {
    }

    /**
     * Validates a value against the Range DSL expression.
     *
     * @param range    raw range DSL from annotation.
     * @param value    value to validate (scalar/array/iterable).
     * @param fieldType declared Java field type.
     * @return {@code true} when value is accepted or when range restrictions are ignored
     * due to invalid/unsatisfiable expressions.
     */
    public static boolean validate(String range, Object value, Class<?> fieldType) {
        if (range == null || range.trim().isEmpty() || value == null) {
            return true;
        }

        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            Class<?> elementType = fieldType.isArray() ? fieldType.getComponentType() : Object.class;
            for (int i = 0; i < len; i++) {
                Object el = Array.get(value, i);
                if (el == null) {
                    return false;
                }
                if (!validate(range, el, elementType)) {
                    return false;
                }
            }
            return true;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item == null) {
                    return false;
                }
                Class<?> elementType = resolveElementType(item, fieldType);
                if (!validate(range, item, elementType)) {
                    return false;
                }
            }
            return true;
        }

        RangeParser.ValueKind kind = isNumericType(fieldType) ? RangeParser.ValueKind.NUMBER : RangeParser.ValueKind.DISCRETE;
        RangeExpression<Object> expr;
        try {
            expr = RangeParser.parse(range, kind);
        } catch (ParseException e) {
            logInvalidRangeOnce(range, e.getMessage());
            return true;
        }

        if (!expr.isSatisfiable()) {
            logUnsatRangeOnce(range);
            return true;
        }

        return expr.matches(value);
    }

    private static boolean isNumericType(Class<?> type) {
        return type.isPrimitive() && type != boolean.class && type != char.class
                || Number.class.isAssignableFrom(type);
    }

    private static Class<?> resolveElementType(Object item, Class<?> declaredType) {
        if (item != null) {
            return item.getClass();
        }
        if (declaredType.isArray()) {
            return declaredType.getComponentType();
        }
        return Object.class;
    }

    private static void logInvalidRangeOnce(String range, String reason) {
        String key = range + " :: " + reason;
        if (INVALID_RANGE_LOGGED.add(key)) {
            LOGGER.error("Invalid range expression '{}': {}", range, reason);
        }
    }

    private static void logUnsatRangeOnce(String range) {
        if (UNSAT_RANGE_LOGGED.add(range)) {
            LOGGER.error("Range expression '{}' is unsatisfiable; ignoring restrictions.", range);
        }
    }
}

