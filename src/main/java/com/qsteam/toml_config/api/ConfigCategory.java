package com.qsteam.toml_config.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Groups configuration parameters into a logical section (category).
 * <p>
 * This annotation should be applied to fields within a {@link TOMLConfig} class.
 * The field <b>must be static</b> (including nested category fields inside a non-root category class).
 * If the field is {@code null} during initialization,
 * the library will attempt to instantiate the category class using a no-arg constructor.
 * <p>
 * In the TOML file, this represents a section header, e.g., {@code [features.machine]}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigCategory {

    /**
     * The name of the category in the TOML file.
     * <p>
     * CamelCase names are automatically converted to snake_case.
     * If empty, the annotated field name is used instead and also converted to snake_case.
     *
     * @return The category name.
     */
    String name() default "";

    /**
     * An optional description added as a comment above the category section.
     *
     * @return The category description.
     */
    String description() default "";
}
