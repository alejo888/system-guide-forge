package com.systemguideforge.backend.application;

public record AccessRequest(String baseUrl, String loginUrl, String username, String password) {}
