package com.rbac_platform.lib.core.error;

/** One invalid input: the field (or parameter) name and a stable snake_case reason code. */
public record FieldViolation(String field, String code) {
}
