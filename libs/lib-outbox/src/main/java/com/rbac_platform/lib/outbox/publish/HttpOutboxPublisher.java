package com.rbac_platform.lib.outbox.publish;

import com.rbac_platform.lib.core.event.EventEnvelope;
import com.rbac_platform.lib.core.event.NonRetryableEventException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Publisher {@code rbac.outbox.publisher=http}: POSTs each event to the client's base URL (the full endpoint URL,
 * e.g. audit-service's /internal/v1/events) with the shared internal token. One request carries one event because the
 * relay publishes one at a time; the receiving endpoint accepts a list all the same.
 *
 * <p>Retry classification: delivered only when the receiver lists the event id in {@code accepted}; a 2xx without it,
 * 408, 429, 5xx and network failures are thrown as they are so the relay retries; any other 4xx will never succeed on
 * a retry, so it is thrown as {@link NonRetryableEventException} and the row is buried. The token and the payload are
 * never logged or put into exception messages.
 */
public class HttpOutboxPublisher implements OutboxPublisher {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final RestClient client;
    private final String internalToken;

    public HttpOutboxPublisher(RestClient client, String internalToken) {
        this.client = client;
        this.internalToken = internalToken;
    }

    @Override
    public void publish(EventEnvelope event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", event.id());
        envelope.put("tenantId", event.tenantId());
        envelope.put("aggregateType", event.aggregateType());
        envelope.put("aggregateId", event.aggregateId());
        envelope.put("eventType", event.eventType());
        envelope.put("payload", event.payload());
        envelope.put("headers", event.headers());
        envelope.put("occurredAt", event.occurredAt().toString());

        JsonNode response;
        try {
            response = client.post()
                    .header(TOKEN_HEADER, internalToken)
                    .body(Map.of("events", List.of(envelope)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = status >= 500 || status == 408 || status == 429;
            if (retryable || !e.getStatusCode().is4xxClientError()) {
                throw e;
            }
            throw new NonRetryableEventException("Event rejected with status " + status, e);
        }
        if (!accepted(response, event)) {
            throw new IllegalStateException("Event " + event.id() + " was not acknowledged by the receiver");
        }
    }

    private static boolean accepted(JsonNode response, EventEnvelope event) {
        JsonNode accepted = response == null ? null : response.get("accepted");
        if (accepted == null || !accepted.isArray()) {
            return false;
        }
        String id = event.id().toString();
        for (JsonNode item : accepted) {
            if (id.equals(item.asString())) {
                return true;
            }
        }
        return false;
    }
}
