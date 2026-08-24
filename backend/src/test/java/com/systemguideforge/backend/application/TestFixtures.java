package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.TargetApplication;

final class TestFixtures {
    static TargetApplication application(String id) {
        TargetApplication application = new TargetApplication("project-id", "App", "http://localhost:8080", "http://localhost:8080/login", "encrypted-user", "encrypted");
        try {
            var field = TargetApplication.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(application, id);
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        return application;
    }
}
