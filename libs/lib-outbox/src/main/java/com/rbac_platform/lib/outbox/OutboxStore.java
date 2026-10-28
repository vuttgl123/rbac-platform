package com.rbac_platform.lib.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * and see every tenant; the claim token ties complete and reschedule to the claim that leased the rows. Complete and
 * reschedule take a whole batch in one statement, so acknowledging a pass costs the same however many events failed.
 */
public class OutboxStore {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String claimSql;
    private final String completeSql;
    private final String rescheduleSql;

    /** One failed event: released for a retry at {@code nextAttemptAt} (null: at once), or buried when {@code dead}. */
    public record Reschedule(UUID id, String error, Instant nextAttemptAt, boolean dead) {

        public Reschedule {
            Objects.requireNonNull(id, "id");
        }
    }

    public OutboxStore(JdbcClient jdbc, JsonMapper json, String schema) {
        this.jdbc = jdbc;
        this.json = json;
        OutboxProperties.requireSchema(schema);
        // The function returns rows in no particular order; id is time-ordered, so sorting by it keeps write order.
        this.claimSql = "select * from " + schema + ".outbox_claim(?, make_interval(secs => ?), ?) order by id";
        this.completeSql = "select " + schema + ".outbox_complete(?, ?::uuid[])";
        // Parallel arrays, sent as text and cast, as the writer does: one statement however many rows.
        this.rescheduleSql = "select " + schema
                + ".outbox_reschedule_many(?, ?::uuid[], ?::text[], ?::timestamptz[], ?::boolean[])";
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

    /** Releases or buries every row in {@code rows} still leased under {@code token}; returns how many it updated. */
    public int reschedule(UUID token, Collection<Reschedule> rows) {
        Objects.requireNonNull(token, "token");
        if (rows.isEmpty()) {
            return 0;
        }
        int n = rows.size();
        String[] ids = new String[n];
        String[] errors = new String[n];
        String[] next = new String[n];
        String[] dead = new String[n];
        int i = 0;
        for (Reschedule row : rows) {
            ids[i] = row.id().toString();
            errors[i] = row.error();
            // Instant.toString is ISO-8601 in UTC, which timestamptz reads exactly (to the microsecond).
            next[i] = row.nextAttemptAt() == null ? null : row.nextAttemptAt().toString();
            dead[i] = Boolean.toString(row.dead());
            i++;
        }
        return jdbc.sql(rescheduleSql).param(token).param(ids).param(errors).param(next).param(dead)
                .query(Integer.class).single();
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
