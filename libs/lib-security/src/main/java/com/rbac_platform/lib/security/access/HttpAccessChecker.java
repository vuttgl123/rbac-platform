package com.rbac_platform.lib.security.access;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.error.ErrorCodes;
import com.rbac_platform.lib.security.Caller;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AccessChecker} that calls authz-service {@code POST /v1/check} with the caller's own bearer token, so
 * the decision is about that caller and no subject is sent. Fails closed: anything other than a clear
 * {@code allowed} is 403 (denied, conditional), 401 (authz rejected the caller's token) or 503 (no usable
 * answer). Failures are logged with the HTTP status, authz's problem {@code code} and any unexpected
 * {@code result}; never the token or the rest of a body.
 */
public class HttpAccessChecker implements AccessChecker {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessChecker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Values from authz are logged only if they cannot forge or break a log line.
    private static final Pattern LOGGABLE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final RestClient authz;

    public HttpAccessChecker(RestClient authz) {
        this.authz = authz;
    }

    @Override
    public void require(Caller caller, String relation, ObjectRef object) {
        String result = check(caller, relation, object);
        if ("allowed".equals(result)) {
            return;
        }
        if ("denied".equals(result) || "conditional".equals(result)) {
            throw ApiException.forbidden("Missing " + relation + " on " + object.type());
        }
        log.warn("authz check returned an unexpected result: {}", loggable(result));
        throw unavailable();
    }

    private String check(Caller caller, String relation, ObjectRef object) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permission", relation);
        body.put("object", Map.of("type", object.type(), "id", object.id()));
        ResponseEntity<String> response;
        try {
            response = authz.post()
                    .uri("/v1/check")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .onStatus(status -> true, (request, reply) -> {
                        // Every status is judged below, where the problem code can be logged.
                    })
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // RestClientException: network failure or timeout.
            log.warn("authz check failed: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
        int status = response.getStatusCode().value();
        if (status != 200) {
            String code = field(response.getBody(), "code");
            log.warn("authz check answered HTTP {}{}", status, code == null ? "" : " with code " + loggable(code));
            if (status == 401) {
                throw ApiException.unauthenticated("Access token rejected by the authorization service");
            }
            throw unavailable();
        }
        return field(response.getBody(), "result");
    }

    /** A string field of a JSON object body, or null when the body is missing, not JSON or lacks it. */
    private static String field(String body, String name) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(body).get(name);
            return value == null || !value.isString() ? null : value.asString();
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String loggable(String value) {
        if (value == null) {
            return "<none>";
        }
        return LOGGABLE.matcher(value).matches() ? value : "<unprintable>";
    }

    private static ApiException unavailable() {
        return ApiException.unavailable(ErrorCodes.AUTHZ_UNAVAILABLE, "Authorization service unavailable");
    }
}
