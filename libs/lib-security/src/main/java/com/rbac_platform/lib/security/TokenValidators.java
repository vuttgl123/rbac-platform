package com.rbac_platform.lib.security;

import com.rbac_platform.lib.core.error.ErrorCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;

/**
 * The checks an access token must pass after its signature is verified. Spring's default timestamp validator is
 * replaced so that expiry has its own error code ({@code token_expired}) that the entry point can tell apart:
 * a client should refresh on that one and re-authenticate on every other.
 */
public final class TokenValidators {

    /** OAuth2 error code for an expired token; equal to the problem code clients see. */
    public static final String TOKEN_EXPIRED = ErrorCodes.TOKEN_EXPIRED;

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private TokenValidators() {
    }

    /**
     * Issuer equals {@code issuer}; {@code exp} present and not past (30 s skew), {@code nbf} not in the future;
     * {@code aud} contains {@code audience}; header {@code typ} is {@code at+jwt}; {@code tid} and {@code sub}
     * (and {@code sid} when present) are UUIDs; {@code tid} is the active tenant that owns {@code slug}, the
     * issuer's own. The tenant lookup runs only when every other check passed.
     */
    public static OAuth2TokenValidator<Jwt> accessToken(String issuer, String slug, String audience,
            TenantStatusCache tenants) {
        OAuth2TokenValidator<Jwt> claims = new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtIssuerValidator(issuer),
                TokenValidators::timestamps,
                new JwtAudienceValidator(audience),
                new JwtTypeValidator("at+jwt"),
                TokenValidators::identifiers));
        return jwt -> {
            OAuth2TokenValidatorResult result = claims.validate(jwt);
            if (result.hasErrors()) {
                return result;
            }
            return tenants.isActive(UUID.fromString(jwt.getClaimAsString("tid")), slug)
                    ? OAuth2TokenValidatorResult.success()
                    : invalid("tenant is not active or does not belong to the issuer");
        };
    }

    private static OAuth2TokenValidatorResult timestamps(Jwt jwt) {
        Instant now = Instant.now();
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            return invalid("exp is required");
        }
        if (now.minus(CLOCK_SKEW).isAfter(expiresAt)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(TOKEN_EXPIRED, "token expired", null));
        }
        Instant notBefore = jwt.getNotBefore();
        if (notBefore != null && now.plus(CLOCK_SKEW).isBefore(notBefore)) {
            return invalid("token not yet valid");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult identifiers(Jwt jwt) {
        List<OAuth2Error> errors = new ArrayList<>();
        if (!isUuid(jwt.getClaims().get("tid"))) {
            errors.add(error("tid must be a UUID"));
        }
        if (!isUuid(jwt.getClaims().get("sub"))) {
            errors.add(error("sub must be a UUID"));
        }
        Object sid = jwt.getClaims().get("sid");
        if (sid != null && !isUuid(sid)) {
            errors.add(error("sid must be a UUID"));
        }
        return OAuth2TokenValidatorResult.failure(errors);
    }

    /** Canonical 8-4-4-4-12 form only; {@link UUID#fromString} alone also accepts shortened groups. */
    static boolean isUuid(Object value) {
        return value instanceof String text && UUID_TEXT.matcher(text).matches();
    }

    private static OAuth2Error error(String description) {
        return new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null);
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(error(description));
    }
}
