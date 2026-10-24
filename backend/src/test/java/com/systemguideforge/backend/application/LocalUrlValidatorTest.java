package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class LocalUrlValidatorTest {
    private final LocalUrlValidator strict = new LocalUrlValidator(false);
    private final LocalUrlValidator dockerHost = new LocalUrlValidator(true);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsLoopbackHttpAndHttps(boolean allowDockerHost) {
        LocalUrlValidator validator = new LocalUrlValidator(allowDockerHost);
        assertTrue(validator.isLocal("http://localhost:3000"));
        assertTrue(validator.isLocal("https://127.0.0.1/login"));
        assertTrue(validator.isLocal("http://[::1]:8080"));
        assertTrue(validator.isLocal("https://[::1]/login"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNonLocalOrUnsupportedUrls(boolean allowDockerHost) {
        LocalUrlValidator validator = new LocalUrlValidator(allowDockerHost);
        assertFalse(validator.isLocal("https://example.com"));
        assertFalse(validator.isLocal("http://example.com"));
        assertFalse(validator.isLocal("ftp://localhost/file"));
        assertFalse(validator.isLocal("not-a-url"));
    }

    @Test
    void rejectsDockerHostWhenTheFlagIsOff() {
        assertFalse(strict.allowsDockerHost());
        assertFalse(strict.isLocal("http://host.docker.internal:4173"));
        assertThrows(IllegalArgumentException.class, () -> strict.requireLocal("http://host.docker.internal:4173"));
    }

    @Test
    void acceptsOnlyTheExactDockerHostWhenTheFlagIsOn() {
        assertTrue(dockerHost.allowsDockerHost());
        assertTrue(dockerHost.isLocal("http://host.docker.internal:4173"));
        assertTrue(dockerHost.isLocal("https://host.docker.internal/login"));
        assertTrue(dockerHost.isLocal("http://HOST.Docker.Internal:8080/app"));
        assertDoesNotThrow(() -> dockerHost.requireLocal("http://host.docker.internal:4173"));
    }

    @Test
    void rejectsDockerHostLookalikesWhenTheFlagIsOn() {
        assertFalse(dockerHost.isLocal("http://foo.host.docker.internal"));
        assertFalse(dockerHost.isLocal("http://host.docker.internal.evil.com"));
        assertFalse(dockerHost.isLocal("ftp://host.docker.internal/file"));
        assertThrows(IllegalArgumentException.class, () -> dockerHost.requireLocal("http://foo.host.docker.internal"));
    }
}
