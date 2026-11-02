package com.rbac_platform.lib.security.access;

import com.rbac_platform.lib.core.error.ApiException;
import com.rbac_platform.lib.core.error.ErrorCodes;
import com.rbac_platform.lib.security.Caller;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * {@code allowed} is either 403 (denied, conditional) or 503 (no usable answer).
 */
public class HttpAccessChecker implements AccessChecker {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessChecker.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient authz;

    public HttpAccessChecker(RestClient authz) {
        this.authz = authz;
    }

    @Override
    public void require(Caller caller, String relation, ObjectRef object) {
        String result = check(caller, relation, object);
        if (!"allowed".equals(result)) {
            if ("denied".equals(result) || "conditional".equals(result)) {
                throw ApiException.forbidden("Missing " + relation + " on " + object.type());
            }
            throw unavailable(null);
        }
    }

    private String check(Caller caller, String relation, ObjectRef object) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permission", relation);
        body.put("object", Map.of("type", object.type(), "id", object.id()));
        try {
            ResponseEntity<String> response = authz.post()
                    .uri("/v1/check")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.writeValueAsString(body))
                    .retrieve()
                    .toEntity(String.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null) {
                throw unavailable(null);
            }
            JsonNode result = JSON.readTree(response.getBody()).get("result");
            return result == null || !result.isString() ? null : result.asString();
        } catch (RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            // RestClientException (status, network, timeout) or JacksonException (garbage body).
            throw unavailable(e);
        }
    }

    private static ApiException unavailable(Exception cause) {
        if (cause != null) {
            log.warn("authz check failed: {}", cause.getClass().getSimpleName());
        }
        return ApiException.unavailable(ErrorCodes.AUTHZ_UNAVAILABLE, "Authorization service unavailable");
    }
}
