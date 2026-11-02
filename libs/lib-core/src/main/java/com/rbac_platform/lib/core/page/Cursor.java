package com.rbac_platform.lib.core.page;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Keyset position of a list: the (timestamp, id) of the last row served. Opaque to clients (base64url of
 * {@code <instant>|<uuid>}) so the sort key can change without breaking the API. Keyset beats OFFSET because
 * the cost stays flat on deep pages and rows inserted meanwhile never shift or duplicate results.
 */
public record Cursor(Instant at, UUID id) {

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((at.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Accepts exactly {@code <ISO-8601 instant>|<UUID>}; anything else is an invalid_cursor validation error. */
    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0 || raw.indexOf('|', bar + 1) >= 0) {
                throw invalid();
            }
            String idPart = raw.substring(bar + 1);
            UUID id = UUID.fromString(idPart);
            if (!id.toString().equalsIgnoreCase(idPart)) { // UUID.fromString tolerates short/odd forms
                throw invalid();
            }
            return new Cursor(Instant.parse(raw.substring(0, bar)), id);
        } catch (RuntimeException e) { // bad base64, bad instant, bad uuid, null input
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.validation(List.of(new FieldViolation("cursor", "invalid_cursor")));
    }
}
