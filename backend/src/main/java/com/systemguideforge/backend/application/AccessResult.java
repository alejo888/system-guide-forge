package com.systemguideforge.backend.application;

public record AccessResult(boolean reachable, boolean authenticated, String message) {}
