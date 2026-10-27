package com.rbac_platform.lib.outbox;

import com.rbac_platform.lib.core.logging.RequestLoggingFilter;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes events into the service's outbox in the caller's transaction, so they commit or roll back together with
 * the change they describe. Call it inside {@code TenantScope.call}: the tenant comes from the transaction's
 * {@code app.tenant_id} and row-level security only lets the current tenant's rows in.
 */
public class OutboxWriter {

    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxWakeup wakeup;
    private final String insertOne;
    private final String insertMany;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json, OutboxWakeup wakeup, String schema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
        OutboxProperties.requireSchema(schema);
        // The tenant is read once per statement from the transaction setting; a missing tenant is a NOT NULL
        // (or row-level security) violation, translated below. No RETURNING or ON CONFLICT: the service role is INSERT-only.
        this.insertOne = "insert into " + schema + ".outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers) "
                + "values ((select util.current_tenant_id()), ?, ?, ?, ?::jsonb, ?::jsonb)";
        // One statement, one round trip, however many messages: the columns arrive as parallel arrays.
        this.insertMany = "insert into " + schema + ".outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers) "
                + "select (select util.current_tenant_id()), t.aggregate_type, t.aggregate_id, t.event_type, t.payload::jsonb, t.headers::jsonb "
                + "from unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) with ordinality "
                + "as t(aggregate_type, aggregate_id, event_type, payload, headers, ord) order by t.ord";
    }

    public void append(OutboxMessage message) {
        Objects.requireNonNull(message, "message");
        requireTransaction();
        execute(() -> jdbc.sql(insertOne)
                .param(message.aggregateType())
                .param(message.aggregateId())
                .param(message.eventType())
                .param(json.writeValueAsString(message.payload()))
                .param(json.writeValueAsString(headersOf(message)))
                .update());
        wakeAfterCommit();
    }

    /** Writes all messages with one statement, so the cost does not grow with the count in round trips. */
    public void appendAll(List<OutboxMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        requireTransaction();
        if (messages.isEmpty()) {
            return;
        }
        int n = messages.size();
        String[] types = new String[n];
        String[] ids = new String[n];
        String[] events = new String[n];
        String[] payloads = new String[n];
        String[] headers = new String[n];
        for (int i = 0; i < n; i++) {
            OutboxMessage m = Objects.requireNonNull(messages.get(i), "message");
            types[i] = m.aggregateType();
            ids[i] = m.aggregateId();
            events[i] = m.eventType();
            payloads[i] = json.writeValueAsString(m.payload());
            headers[i] = json.writeValueAsString(headersOf(m));
        }
        execute(() -> jdbc.sql(insertMany)
                .param(types).param(ids).param(events).param(payloads).param(headers)
                .update());
        wakeAfterCommit();
    }

    private Map<String, String> headersOf(OutboxMessage message) {
        String requestId = MDC.get(RequestLoggingFilter.MDC_KEY);
        if (requestId == null || message.headers().containsKey("requestId")) {
            return message.headers();
        }
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        headers.put("requestId", requestId);
        return headers;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter must run inside the business transaction; call it from within TenantScope.call");
        }
    }

    private void execute(Runnable insert) {
        try {
            insert.run();
        } catch (DataAccessException e) {
            if (missingTenant(e)) {
                throw new IllegalStateException(
                        "OutboxWriter found no tenant on the transaction; call it from within TenantScope.call", e);
            }
            throw e;
        }
    }

    /** NOT NULL on tenant_id, or the row-level-security check that a null tenant fails first. */
    private static boolean missingTenant(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (NOT_NULL_VIOLATION.equals(state)) {
                    return true;
                }
                return INSUFFICIENT_PRIVILEGE.equals(state) && String.valueOf(sql.getMessage()).contains("row-level security");
            }
        }
        return false;
    }

    /** One synchronization per transaction (and writer), however many appends it makes. */
    private void wakeAfterCommit() {
        for (TransactionSynchronization existing : TransactionSynchronizationManager.getSynchronizations()) {
            if (existing instanceof WakeAfterCommit mine && mine.owner == this) {
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new WakeAfterCommit(this));
    }

    private static final class WakeAfterCommit implements TransactionSynchronization {

        private final OutboxWriter owner;

        WakeAfterCommit(OutboxWriter owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            owner.wakeup.signal();
        }
    }
}
