package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalUrlValidatorTest {
    @Test
    void acceptsLoopbackHttpAndHttps() {
        assertTrue(LocalUrlValidator.isLocal("http://localhost:3000"));
        assertTrue(LocalUrlValidator.isLocal("https://127.0.0.1/login"));
        assertTrue(LocalUrlValidator.isLocal("http://[::1]:8080"));
    }

    @Test
    void rejectsNonLocalOrUnsupportedUrls() {
        assertFalse(LocalUrlValidator.isLocal("https://example.com"));
        assertFalse(LocalUrlValidator.isLocal("ftp://localhost/file"));
        assertFalse(LocalUrlValidator.isLocal("not-a-url"));
    }
}
