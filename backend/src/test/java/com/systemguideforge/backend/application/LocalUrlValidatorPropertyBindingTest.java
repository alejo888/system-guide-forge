package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@code SGF_ALLOW_DOCKER_HOST} reaches {@code sgf.allow-docker-host} through the real
 * {@code application.properties}. Only the validator is registered: no auto-configuration, so no datasource.
 */
class LocalUrlValidatorPropertyBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Drop the host OS environment and JVM system properties so a developer's own SGF_ALLOW_DOCKER_HOST
            // (or a -DSGF_ALLOW_DOCKER_HOST / -Dsgf.allow-docker-host) cannot leak into the test.
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LocalUrlValidator.class);

    @Test
    void dockerHostIsOffWhenTheVariableIsUnset() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LocalUrlValidator.class).allowsDockerHost()).isFalse();
        });
    }

    @Test
    void dockerHostIsOnWhenTheVariableIsTrue() {
        runner.withPropertyValues("SGF_ALLOW_DOCKER_HOST=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LocalUrlValidator.class).allowsDockerHost()).isTrue();
        });
    }
}
