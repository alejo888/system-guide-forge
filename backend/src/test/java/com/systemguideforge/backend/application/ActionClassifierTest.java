package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActionClassifierTest {
    @Test
    void classifiesNavigationAsSafeAndSubmissionAsMutating() {
        assertThat(ActionClassifier.classify("a", "href", "/help")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classify("button", "type", "submit")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classify("div", "onclick", "doSomething()")).isEqualTo(ActionClassification.UNKNOWN);
    }
}
