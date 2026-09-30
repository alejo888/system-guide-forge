package com.systemguideforge.backend.application;

/** message stays as an English diagnostic for API consumers; the UI translates code (nullable for legacy producers). */
public record AccessResult(boolean reachable, boolean authenticated, String message, AccessResultCode code) {
    public AccessResult(boolean reachable, boolean authenticated, String message) { this(reachable, authenticated, message, null); }
}
