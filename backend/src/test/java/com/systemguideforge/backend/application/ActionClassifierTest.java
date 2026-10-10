package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void classifiesActivationTogglesAsMutatingInEnglishAndSpanish() {
        assertThat(ActionClassifier.classifyControl("button", null, "Desactivar")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", null, "Activar")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Deshabilitar usuario")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Habilitarlo")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Deactivate user")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Activate")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Disable account")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "Enable")).isEqualTo(ActionClassification.MUTATING);
    }

    @Test
    void classifiesSpanishImperativesInTuVosAndUstedFormsAsMutating() {
        List.of("Guarda cambios", "Elimina proyecto", "Borra", "Envía", "Envia", "Actualiza", "Quita", "Agrega miembro",
                "Añade", "Crea tarea", "Activa", "Desactiva", "Habilita", "Deshabilita", "Cierra sesión")
                .forEach(name -> assertMutating(name));
        List.of("Guardá", "Eliminá", "Borrá", "Enviá", "Actualizá", "Quitá", "Agregá", "Añadí", "Creá", "Activá",
                "Desactivá", "Habilitá", "Cerrá sesión")
                .forEach(name -> assertMutating(name));
        List.of("Guarde", "Elimine", "Borre", "Envíe", "Actualice", "Quite", "Agregue", "Añada", "Cree", "Desactive",
                "Habilite", "Deshabilite", "Cierre sesión")
                .forEach(name -> assertMutating(name));
    }

    @Test
    void classifiesSpanishImperativesWithAttachedPronounsAsMutating() {
        List.of("Elimínalo", "Guárdalo", "Bórrelo", "Envíalo", "Actualízalo", "Añádelo", "Quítame", "Créala",
                "Guardalo", "Elimínelos")
                .forEach(name -> assertMutating(name));
    }

    @Test
    void classifiesConfirmPublishArchiveApproveAndPayAsMutating() {
        List.of("Confirm", "Publish post", "Archive", "Approve request", "Pay now")
                .forEach(name -> assertMutating(name));
        List.of("Confirmar", "Confirma", "Confirmá", "Confirme", "Publicar", "Publica", "Publicá", "Publique",
                "Publícalo", "Archivar", "Archiva", "Archivá", "Archívalo", "Aprobar", "Aprueba", "Aprobá", "Apruebe",
                "Apruébalo", "Pagar factura", "Paga", "Pagá", "Pague", "Págalo")
                .forEach(name -> assertMutating(name));
    }

    @Test
    void matchesDecomposedAccentsAfterNfcNormalization() {
        assertMutating("Cerrar sesio\u0301n");
        assertMutating("An\u0303adir miembro");
        assertMutating("Gua\u0301rdalo");
        assertMutating("Elimina\u0301");
    }

    @Test
    void classifiesImageInputsAsSubmitControls() {
        assertThat(ActionClassifier.classify("input", "type", "image")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classify("input", "type", " IMAGE ")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("input", "image", "Buscar")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classify("img", "type", "image")).isEqualTo(ActionClassification.UNKNOWN);
    }

    @Test
    void keepsNounsAndStatusesSharingVerbStemsAsUnknown() {
        List.of("Creación de tareas", "Pagos", "Pago", "Página siguiente", "Next page", "Page 2", "Archivo", "Archivos",
                "Activas", "Activos", "Publicaciones", "Vista pública", "Confirmación", "Confirmed", "Borrador",
                "Envíos", "Guardados", "Aprobación", "Aprobado", "Payments", "Eliminados", "Actualizaciones")
                .forEach(name -> assertThat(ActionClassifier.classifyControl("button", "button", name))
                        .as(name).isEqualTo(ActionClassification.UNKNOWN));
    }

    @Test
    void keepsStateLabelsSharingActivationStemsAsUnknown() {
        assertThat(ActionClassifier.classifyControl("button", "button", "Active users")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Enabled")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Disabled")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Actividad")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Habilitado")).isEqualTo(ActionClassification.UNKNOWN);
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
    void classifiesTabControlsAsSafeViewSwitchesUnlessTheirNameMutates() {
        assertThat(ActionClassifier.classifyControl("button", "button", "Por hacer", "tab")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classifyControl("button", null, "En progreso", " TAB ")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classifyControl("button", "button", "Crear tarea", "tab")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "submit", "Resumen", "tab")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyControl("button", "button", "API tokens", "tab")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Por hacer", null)).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyControl("button", "button", "Por hacer", "button")).isEqualTo(ActionClassification.UNKNOWN);
    }

    @Test
    void acceptsExplicitFixtureMetadataWithoutChangingDefaultRules() {
        assertThat(ActionClassifier.classifyFixtureMetadata("SAFE")).isEqualTo(ActionClassification.SAFE);
        assertThat(ActionClassifier.classifyFixtureMetadata("MUTATING")).isEqualTo(ActionClassification.MUTATING);
        assertThat(ActionClassifier.classifyFixtureMetadata("UNKNOWN")).isEqualTo(ActionClassification.UNKNOWN);
        assertThat(ActionClassifier.classifyFixtureMetadata("not-a-classification")).isNull();
        assertThat(ActionClassifier.classify("button", "type", "button")).isEqualTo(ActionClassification.UNKNOWN);
    }

    private static void assertMutating(String name) {
        assertThat(ActionClassifier.classifyControl("button", "button", name)).as(name).isEqualTo(ActionClassification.MUTATING);
    }
}
