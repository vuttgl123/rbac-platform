package com.rbac_platform.lib.outbox;

import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * An event to publish, as the code that makes the change describes it. The writer adds the tenant, the id and
 * the request id. Keep the payload small (ids plus the changed fields).
 */
public record OutboxMessage(String aggregateType, String aggregateId, String eventType,
                            JsonNode payload, Map<String, String> headers) {

    public OutboxMessage {
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static OutboxMessage of(String aggregateType, String aggregateId, String eventType, JsonNode payload) {
        return new OutboxMessage(aggregateType, aggregateId, eventType, payload, Map.of());
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
