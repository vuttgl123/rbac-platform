package com.rbac_platform.lib.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, taken from a verified access token. Controllers declare a {@code Caller} parameter instead of
 * reading the security context, so the tenant every query must be scoped to is always explicit.
 *
 * @param tenantId    the {@code tid} claim
 * @param subjectType {@code user} for now (service subjects come later)
 * @param subjectId   the {@code sub} claim
 * @param sessionId   the {@code sid} claim, or null when the token has none
 * @param amr         authentication methods used, e.g. {@code ["pwd","otp"]}, for step-up decisions
 * @param issuer      the tenant's issuer URL
 * @param token       the raw bearer token, for forwarding to another service; never logged
 */
public record Caller(UUID tenantId, String subjectType, UUID subjectId, UUID sessionId, List<String> amr,
        String issuer, String token) {

    public Caller {
        amr = amr == null ? List.of() : List.copyOf(amr);
    }

    /** Expects a token that passed {@link TokenValidators#accessToken}, which guarantees the UUID claims parse. */
    public static Caller from(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        return new Caller(
                UUID.fromString(jwt.getClaimAsString("tid")),
                "user",
                UUID.fromString(jwt.getSubject()),
                sid == null ? null : UUID.fromString(sid),
                jwt.getClaimAsStringList("amr"),
                jwt.getClaimAsString("iss"),
                jwt.getTokenValue());
    }

    public boolean hasAmr(String method) {
        return amr.contains(method);
    }

    /** Leaves the bearer token out so a logged Caller cannot be replayed. */
    @Override
    public String toString() {
        return "Caller[tenantId=" + tenantId + ", subjectType=" + subjectType + ", subjectId=" + subjectId
                + ", sessionId=" + sessionId + ", amr=" + amr + ", issuer=" + issuer + "]";
    }
}
