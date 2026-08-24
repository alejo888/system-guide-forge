package com.systemguideforge.backend.application;

public interface CredentialProtector {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
