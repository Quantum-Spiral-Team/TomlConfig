package com.qsteam.toml_config.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;

/**
 * Marks a field as a configuration parameter.
 * <p>
 * Supports automatic conversion for various types including primitives, Strings,
 * arrays, Collections, {@link UUID}, and {@link Enum}.
 * If a value is missing in the TOML file, it will be populated using the
 * field's default value.
 * <p>
 * If {@link #range()} is omitted, TOMLConfig may derive a type-based range for documentation
 * (and may use it for validation where applicable). For enums with a small number of constants,
 * the allowed constants can be emitted automatically.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigValue {

    /**
     * The key for the parameter in the TOML file.
     * If left empty, the field name will be used and converted to snake_case
     * (e.g., {@code maxPower} becomes {@code max_power}).
     *
     * @return The configuration key.
     */
    String key() default "";

    /**
     * A comment added above the parameter in the TOML file.
     *
     * @return The parameter description.
     */
    String comment() default "";

    /**
     * Optional constraint for values loaded from TOML.
     * <p>
     * The syntax is a small boolean DSL:
     * <ul>
     *   <li><b>Set literal</b>: {@code {a,b,c}} (equality/whitelist)</li>
     *   <li><b>Intervals (numeric only)</b>:
     *     {@code [a..b]}, {@code (a..b]}, {@code [a..b)}, {@code (a..b)}.
     *     Bounds may be omitted for infinity: {@code (..64]}, {@code [59..)}.</li>
     *   <li><b>Operators</b>: {@code !} (NOT), {@code &} (AND), {@code |} (OR)</li>
     *   <li><b>Grouping</b>: parentheses. Note that {@code (...)} can also represent an interval
     *   when it contains the {@code ..} token.</li>
     * </ul>
     * <p>
     * Whitespace is ignored. If the expression is invalid or unsatisfiable, restrictions are ignored
     * and a message is logged (values from TOML are then accepted without range enforcement until the expression is fixed).
     *
     * @return Range expression string, or empty for no explicit range.
     */
    String range() default "";
}
