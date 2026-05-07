package com.qsteam.toml_config.api;

import com.qsteam.toml_config.core.TOMLClassTransformer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Root annotation to define a TOML configuration class.
 * <p>
 * Classes marked with this annotation are automatically discovered during the
 * pre-initialization phase. An ASM transformer will inject a call to the
 * configuration initializer into the class's static initializer ({@code <clinit>}).
 * See {@link TOMLClassTransformer}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface TOMLConfig {

    /**
     * The Mod ID associated with this configuration.
     * Used to generate the {@code [mod_info]} header in the TOML file.
     *
     * @return The ID of the owner mod.
     */
    String modId();

    /**
     * The name of the configuration file (with or without the .toml extension).
     * Defaults to {@code general}.
     *
     * @return The file name.
     */
    String name() default "general";

    /**
     * The root directory for the configuration file relative to the game directory.
     * Defaults to the standard {@code config} folder.
     *
     * @return The target directory path.
     */
    String rootDir() default "config";

}
