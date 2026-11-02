package com.rbac_platform.lib.crypto;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Password hashing for every service; the envelope cipher only where rbac.crypto.kek is set. A bad KEK fails startup. */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty("rbac.crypto.kek")
    EnvelopeCipher envelopeCipher(CryptoProperties properties) {
        return new EnvelopeCipher(Kek.fromBase64(properties.kekId(), properties.kek()));
    }
}
