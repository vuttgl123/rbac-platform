package com.rbac_platform.lib.outbox;

import com.rbac_platform.lib.core.event.EventEnvelope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One claimed outbox row. {@code attempts} counts this claim. */
public record OutboxRecord(UUID id, UUID tenantId, String aggregateType, String aggregateId, String eventType,
                           JsonNode payload, Map<String, String> headers, Instant createdAt, int attempts) {

    /** The transport-neutral view handed to publishers and handlers; the event happened when the row was written. */
    public EventEnvelope toEnvelope() {
        return new EventEnvelope(id, tenantId, aggregateType, aggregateId, eventType, payload, headers, createdAt);
    }
}
