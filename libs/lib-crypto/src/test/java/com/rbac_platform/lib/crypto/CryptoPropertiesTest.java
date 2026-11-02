package com.rbac_platform.lib.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CryptoPropertiesTest {

    @Test
    void toStringRedactsKek() {
        String kek = "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LTEyMzQ=";
        assertThat(new CryptoProperties(kek, "env-1").toString()).doesNotContain(kek).contains("kekId=env-1");
        assertThat(new CryptoProperties(null, "env-1").toString()).contains("kek=null");
    }
}
