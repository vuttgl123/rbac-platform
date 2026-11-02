package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbac_platform.lib.core.http.HttpClientProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which issuers get a decoder at all; no network is involved until a decoder sees a token. */
class TenantJwtDecodersTest {

    private static final String PREFIX = "https://id.example.com/t/";
    private static final UUID TENANT = UUID.randomUUID();

    /** Every syntactically valid slug except "gone" belongs to an active tenant. */
    private static final class AllActive extends TenantStatusCache {
        AllActive() {
            super(null, Duration.ofSeconds(30));
        }

        @Override
        public UUID activeTenant(String slug) {
            return slug.equals("gone") ? null : TENANT;
        }
    }

    private final TenantJwtDecoders decoders = new TenantJwtDecoders(PREFIX, "rbac-api", new AllActive(),
            new HttpClientProperties(Duration.ofSeconds(1), Duration.ofSeconds(2)));

    @Test
    void prefixPlusSlugOfActiveTenantIsTrusted() {
        assertThat(decoders.decoderFor(PREFIX + "acme")).isNotNull();
        assertThat(decoders.decoderFor(PREFIX + "a")).isNotNull();
        assertThat(decoders.decoderFor(PREFIX + "acme-2")).isNotNull();
    }

    @Test
    void slugWithoutActiveTenantIsNotTrusted() {
        assertThat(decoders.decoderFor(PREFIX + "gone")).isNull();
    }

    @Test
    void anythingElseIsNotTrusted() {
        assertThat(decoders.decoderFor(null)).isNull();
        assertThat(decoders.decoderFor(PREFIX)).isNull();
        assertThat(decoders.decoderFor(PREFIX + "acme/")).isNull();
        assertThat(decoders.decoderFor(PREFIX + "acme/extra")).isNull();
        assertThat(decoders.decoderFor(PREFIX + "Acme")).isNull();
        assertThat(decoders.decoderFor(PREFIX + "-acme")).isNull();
        assertThat(decoders.decoderFor(PREFIX + "acme-")).isNull();
        assertThat(decoders.decoderFor(PREFIX + "a".repeat(64))).isNull();
        assertThat(decoders.decoderFor(PREFIX + "acme?x=1")).isNull();
        assertThat(decoders.decoderFor("http://id.example.com/t/acme")).isNull();
        assertThat(decoders.decoderFor("https://id.example.com.evil/t/acme")).isNull();
    }
}
