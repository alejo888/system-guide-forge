package com.systemguideforge.backend.application;

public interface AccessProbe {
    AccessResult test(AccessRequest request);
}
