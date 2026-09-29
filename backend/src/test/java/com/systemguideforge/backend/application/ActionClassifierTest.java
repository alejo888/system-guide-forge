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
    void classifiesControlsAsMutatingFromTheirAccessibleNameInEnglishAndSpanish() {
        assertThat(ActionClassifier.classifyControl("button", "button", "Create task")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", null, "Add member")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Log out")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Crear tarea")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Cerrar sesión")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Eliminar proyecto")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Añadir miembro")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("input", "submit", "Buscar")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Quitarme del proyecto")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Eliminarlo")).isEqualTo(ActionClassification.MUTATING);
    }

    @Test
    void keepsControlsWithoutAMutatingVerbAsUnknown() {
        assertThat(ActionClassifier.classifyControl("button", "button", "Abrir menú")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Mostrar contraseña")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Recreate view")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", null)).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("div", null, "Crear tarea")).isEqualTo(ActionClassification.UNKNOWN);
    }

    @Test
    void classifiesTextEntryFieldsByTypeOnlyAndIgnoresTheirLabel() {
        assertThat(ActionClassifier.classifyControl("input", "text", "Add a comment")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("input", null, "save-draft")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("input", "search", "Agregar filtro")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("textarea", null, "Agregar comentario")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("input", "button", "Eliminar")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("input", "reset", "Borrar formulario")).isEqualTo(ActionClassification.MUTATING);
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
