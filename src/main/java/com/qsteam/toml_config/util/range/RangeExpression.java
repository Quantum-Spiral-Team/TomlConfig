package com.qsteam.toml_config.util.range;

import java.util.Set;

public interface RangeExpression<T> {
    boolean matches(T value);

    /**
     * Returns true if this expression can match at least one value.
     * If false, the range is treated as invalid and should be ignored.
     */
    boolean isSatisfiable();

    /**
     * Values mentioned explicitly in set literals (for non-numeric satisfiable checks).
     */
    Set<String> mentionedLiterals();
}

