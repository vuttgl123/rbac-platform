package com.rbac_platform.lib.outbox;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox settings (rbac.outbox.*). Bad values fail startup with a message naming the property.
 *
 * @param schema       schema that owns the service's outbox table; it is concatenated into SQL, so it must be a plain
 *                     lower-case identifier. Null only while the outbox is not configured (the auto-configuration is
 *                     then inactive)
 * @param enabled      whether this instance runs the relay
 * @param publisher    name of the publisher strategy the relay uses
 * @param batchSize    rows claimed per relay pass
 * @param pollInterval longest wait between relay passes when no writer wakes the relay
 * @param lease        how long a claimed row is held before another relay may claim it again; at most an hour
 * @param maxAttempts  deliveries tried before a row is buried
 * @param backoff      delay between a failed delivery and the next one
 */
@ConfigurationProperties("rbac.outbox")
public record OutboxProperties(String schema,
                               @DefaultValue("true") boolean enabled,
                               @DefaultValue("log") String publisher,
                               @DefaultValue("100") int batchSize,
                               @DefaultValue("500ms") Duration pollInterval,
                               @DefaultValue("60s") Duration lease,
                               @DefaultValue("10") int maxAttempts,
                               @DefaultValue Backoff backoff) {

    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Duration MAX_LEASE = Duration.ofHours(1);

    /** @param initial delay after the first failure @param max ceiling of the delay as failures accumulate */
    public record Backoff(@DefaultValue("1s") Duration initial, @DefaultValue("5m") Duration max) {

        public Backoff {
            requirePositive(initial, "rbac.outbox.backoff.initial");
            requirePositive(max, "rbac.outbox.backoff.max");
        }
    }

    public OutboxProperties {
        if (schema != null) {
            requireSchema(schema);
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("rbac.outbox.batch-size must be between 1 and 1000 but was " + batchSize);
        }
        requirePositive(pollInterval, "rbac.outbox.poll-interval");
        requirePositive(lease, "rbac.outbox.lease");
        // outbox_claim refuses longer leases: a relay holding rows that long has hung, not worked.
        if (lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("rbac.outbox.lease must be at most " + MAX_LEASE + " but was " + lease);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("rbac.outbox.max-attempts must be at least 1 but was " + maxAttempts);
        }
    }

    /** The one place the schema name is vetted before it is concatenated into SQL. */
    static String requireSchema(String schema) {
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("rbac.outbox.schema must match [a-z_][a-z0-9_]* but was '" + schema + "'");
        }
        return schema;
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration but was " + value);
        }
    }
}
