package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionClassifierTest {
    @Test
    void classifiesNavigationAsSafeAndSubmissionAsMutating() {
        assertThat(ActionClassifier.classify("a", "href", "/help")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classify("a", "href", "https://external.example/help")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classify("button", "type", "submit")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classify("button", "type", "button")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classify("div", "onclick", "doSomething()")).isEqualTo(ActionClassification.UNKNOWN);
    }

    @Test
    void treatsSensitiveFieldsAsUnknownRatherThanExecutableActions() {
        assertThat(ActionClassifier.classify("input", "type", "password")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classify("input", "name", "api-key")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classify("input", "name", "token")).isEqualTo(ActionClassification.UNKNOWN);
    }

    @Test
    void acceptsExplicitFixtureMetadataWithoutChangingDefaultRules() {
        assertThat(ActionClassifier.classifyFixtureMetadata("SAFE")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classifyFixtureMetadata("MUTATING")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyFixtureMetadata("UNKNOWN")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyFixtureMetadata("not-a-classification")).isNull();
        assertThat(ActionClassifier.classify("button", "type", "button")).isEqualTo(ActionClassification.UNKNOWN);
    }
}
