package com.rbac_platform.lib.core.error;

/** Stable machine-readable error codes carried in the {@code code} field of every problem response. */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "validation_failed";
    public static final String UNAUTHENTICATED = "unauthenticated";
    public static final String TOKEN_EXPIRED = "token_expired";
    public static final String FORBIDDEN = "forbidden";
    public static final String MFA_STEP_UP_REQUIRED = "mfa_step_up_required";
    public static final String NOT_FOUND = "not_found";
    public static final String TENANT_NOT_FOUND = "tenant_not_found";
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String INVALID_CHALLENGE = "invalid_challenge";
    public static final String INVALID_MFA_CODE = "invalid_mfa_code";
    public static final String PASSWORD_POLICY_VIOLATION = "password_policy_violation";
    public static final String INVITATION_INVALID = "invitation_invalid";
    public static final String REFRESH_TOKEN_INVALID = "refresh_token_invalid";
    public static final String SLUG_TAKEN = "slug_taken";
    public static final String EMAIL_TAKEN = "email_taken";
    public static final String UNKNOWN_RESOURCE_TYPE = "unknown_resource_type";
    public static final String UNKNOWN_RELATION = "unknown_relation";
    public static final String MODEL_REVISION_CONFLICT = "model_revision_conflict";
    public static final String RANGE_TOO_LARGE = "range_too_large";
    public static final String AUTHZ_UNAVAILABLE = "authz_unavailable";
    public static final String INTERNAL_ERROR = "internal_error";
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";

    private ErrorCodes() {
    }
}
