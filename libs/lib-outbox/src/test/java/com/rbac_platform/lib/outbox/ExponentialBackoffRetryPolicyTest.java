package com.rbac_platform.lib.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rbac_platform.lib.core.event.NonRetryableEventException;
import com.rbac_platform.lib.outbox.retry.ExponentialBackoffRetryPolicy;
import com.rbac_platform.lib.outbox.retry.RetryDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ExponentialBackoffRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final RuntimeException FAILURE = new IllegalStateException("downstream unavailable");

    private static ExponentialBackoffRetryPolicy policy(int maxAttempts) {
        return new ExponentialBackoffRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), maxAttempts,
                new SplittableRandom(42));
    }

    private static OutboxRecord record(int attempts) {
        return new OutboxRecord(UUID.randomUUID(), UUID.randomUUID(), "user", "u-1", "user.updated",
                JsonNodeFactory.instance.objectNode(), Map.of(), NOW.minusSeconds(5), attempts);
    }

    /** The delay the policy picked, after checking it is a retry. */
    private static Duration delay(ExponentialBackoffRetryPolicy policy, int attempts, Throwable failure) {
        RetryDecision decision = policy.decide(record(attempts), failure, NOW);
        assertThat(decision).as("attempts %d", attempts).isInstanceOf(RetryDecision.RetryAt.class);
        return Duration.between(NOW, ((RetryDecision.RetryAt) decision).at());
    }

    @Test
    void delaysDoubleWithinTheJitterBand() {
        ExponentialBackoffRetryPolicy policy = policy(10);
        // Many draws per attempt count, so the whole band is exercised rather than one lucky value.
        for (int i = 0; i < 200; i++) {
            assertThat(delay(policy, 1, FAILURE)).isBetween(Duration.ofMillis(800), Duration.ofSeconds(1));
            assertThat(delay(policy, 2, FAILURE)).isBetween(Duration.ofMillis(1600), Duration.ofSeconds(2));
            assertThat(delay(policy, 4, FAILURE)).isBetween(Duration.ofMillis(6400), Duration.ofSeconds(8));
            assertThat(delay(policy, 9, FAILURE)).isBetween(Duration.ofMillis(204_800), Duration.ofSeconds(256));
        }
        assertThat(policy.decide(record(10), FAILURE, NOW)).isInstanceOf(RetryDecision.Bury.class);
        assertThat(policy.decide(record(11), FAILURE, NOW)).isInstanceOf(RetryDecision.Bury.class);
    }

    @Test
    void delayIsCappedAtMax() {
        ExponentialBackoffRetryPolicy policy = policy(20);
        for (int i = 0; i < 200; i++) {
            assertThat(delay(policy, 15, FAILURE)).isBetween(Duration.ofSeconds(240), Duration.ofSeconds(300));
        }
        // Far past any shift that fits in a long: still the cap, no overflow.
        ExponentialBackoffRetryPolicy patient = policy(Integer.MAX_VALUE);
        assertThat(delay(patient, 200, FAILURE)).isBetween(Duration.ofSeconds(240), Duration.ofSeconds(300));
    }

    @Test
    void nonRetryableCauseIsBuriedImmediately() {
        RuntimeException wrapped = new RuntimeException("handler failed",
                new NonRetryableEventException("payload can never be processed"));
        RetryDecision decision = policy(10).decide(record(1), wrapped, NOW);
        assertThat(decision).isInstanceOf(RetryDecision.Bury.class);
        assertThat(((RetryDecision.Bury) decision).reason()).isNotBlank();
    }

    @Test
    void invalidSettingsAreRejected() {
        SplittableRandom random = new SplittableRandom(1);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ZERO, Duration.ofMinutes(5), 10, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1), 10, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), 0, random))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), 10, null))
                .isInstanceOf(NullPointerException.class);
    }
}
