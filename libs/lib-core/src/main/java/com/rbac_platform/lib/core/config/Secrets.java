package com.rbac_platform.lib.core.config;

/** One rule for shared secrets in configuration, so every property that holds one rejects the same mistakes. */
public final class Secrets {

    private static final int MIN_LENGTH = 32;

    private Secrets() {
    }

    /**
     * Returns the value when it can serve as a secret: at least 32 characters, no unresolved {@code ${...}} placeholder
     * (Boot leaves one as literal text, which would be a publicly known secret) and no leading or trailing whitespace.
     *
     * @throws IllegalArgumentException with a message starting with the property name; the value is never included
     */
    public static String requireSecret(String property, String value) {
        if (value == null || value.isBlank() || value.length() < MIN_LENGTH || value.contains("${")
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException(property + " is required: at least " + MIN_LENGTH
                    + " characters, no unresolved ${...} placeholder, no leading or trailing whitespace");
        }
        return value;
    }
}
