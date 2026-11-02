package com.rbac_platform.lib.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** Which issuers get a decoder at all; no network is involved until a decoder sees a token. */
class TenantJwtDecodersTest {

    private static final String PREFIX = "https://id.example.com/t/";

    private final TenantJwtDecoders decoders =
            new TenantJwtDecoders(PREFIX, "rbac-api", new TenantStatusCache(null, Duration.ofSeconds(30)), new RestTemplate());

    @Test
    void prefixPlusSlugIsTrusted() {
        assertThat(decoders.decoderFor(PREFIX + "acme")).isNotNull();
        assertThat(decoders.decoderFor(PREFIX + "a")).isNotNull();
        assertThat(decoders.decoderFor(PREFIX + "acme-2")).isNotNull();
    }

    @Test
    void sameIssuerGetsSameDecoder() {
        assertThat(decoders.decoderFor(PREFIX + "acme")).isSameAs(decoders.decoderFor(PREFIX + "acme"));
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
