package com.systemguideforge.backend.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {
    @Bean
    CredentialProtector credentialProtector(
            @Value("${sgf.credential-key:}") String configuredKey) {
        String key = configuredKey.isBlank() ? System.getenv("SGF_CREDENTIAL_KEY") : configuredKey;
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Configure SGF_CREDENTIAL_KEY or sgf.credential-key explicitly; no default key is available");
        }
        return new AesCredentialProtector(key);
    }
}
