package com.rbac_platform.lib.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The relay's view of the outbox: each method is one call to a V12 function on the caller's connection, so it
 * joins the caller's transaction when there is one and autocommits otherwise. The functions run as the table owner
 * and see every tenant; the claim token ties complete and reschedule to the claim that leased the rows.
 */
public class OutboxStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String claimSql;
    private final String completeSql;
    private final String rescheduleSql;

    public OutboxStore(JdbcClient jdbc, JsonMapper json, String schema) {
        this.jdbc = jdbc;
        this.json = json;
        OutboxProperties.requireSchema(schema);
        // The function returns rows in no particular order; id is time-ordered, so sorting by it keeps write order.
        this.claimSql = "select * from " + schema + ".outbox_claim(?, make_interval(secs => ?), ?) order by id";
        this.completeSql = "select " + schema + ".outbox_complete(?, ?::uuid[])";
        this.rescheduleSql = "select " + schema + ".outbox_reschedule(?, ?, ?::text, ?::timestamptz, ?)";
    }

    /** Leases up to {@code limit} due rows for {@code lease}, counting an attempt on each, and returns them sorted by id. */
    public List<OutboxRecord> claim(int limit, Duration lease, UUID token) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(token, "token");
        return jdbc.sql(claimSql)
                .param(limit)
                .param(lease.toNanos() / 1_000_000_000.0)
                .param(token)
                .query(this::map)
                .list();
    }

    /** Deletes delivered rows still leased under {@code token}; returns how many it deleted. */
    public int complete(UUID token, Collection<UUID> ids) {
        Objects.requireNonNull(token, "token");
        if (ids.isEmpty()) {
            return 0;
        }
        String[] array = ids.stream().map(UUID::toString).toArray(String[]::new);
        return jdbc.sql(completeSql).param(token).param(array).query(Integer.class).single();
    }

    /** Releases a leased row for a retry at {@code nextAttemptAt}, or buries it; false when the token no longer holds the lease. */
    public boolean reschedule(UUID token, UUID id, String error, Instant nextAttemptAt, boolean dead) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(id, "id");
        OffsetDateTime next = nextAttemptAt == null ? null : nextAttemptAt.atOffset(ZoneOffset.UTC);
        return jdbc.sql(rescheduleSql).param(token).param(id).param(error).param(next).param(dead)
                .query(Boolean.class).single();
    }

    private OutboxRecord map(ResultSet rs, int row) throws SQLException {
        return new OutboxRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                json.readTree(rs.getString("payload")),
                json.readValue(rs.getString("headers"), STRING_MAP),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"));
    }
}
