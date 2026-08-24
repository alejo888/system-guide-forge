package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CredentialProtectorTest {
    private final CredentialProtector protector = new AesCredentialProtector("test-key");

    @Test
    void rejectsBlankEncryptionKey() {
        assertThrows(IllegalArgumentException.class, () -> new AesCredentialProtector(" "));
    }

    @Test
    void encryptsWithoutKeepingPlaintextAndCanDecrypt() {
        String encrypted = protector.encrypt("secret-password");
        assertNotEquals("secret-password", encrypted);
        assertFalse(encrypted.contains("secret-password"));
        assertEquals("secret-password", protector.decrypt(encrypted));
    }
}
