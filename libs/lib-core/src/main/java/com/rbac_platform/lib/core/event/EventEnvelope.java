package com.rbac_platform.lib.core.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One event as it travels from the outbox to its handlers, whatever the transport. {@code id} is stable
 * across redeliveries, so handlers use it to detect duplicates.
 */
public record EventEnvelope(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                            JsonNode payload, Map<String, String> headers, Instant occurredAt) {

    public EventEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(occurredAt, "occurredAt");
        headers = Map.copyOf(headers);
    }
}
