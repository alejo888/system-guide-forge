package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.ApprovedOpenerRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovedOpenerMatcherTest {
    private final ApprovedOpenerMatcher matcher = new ApprovedOpenerMatcher(List.of(
            new ApprovedOpenerRule("/projects/{id}/board", "Crear tarea"),
            new ApprovedOpenerRule("/projects", "Crear proyecto")));

    @Test
    void matchesAnApprovedNameOnAnyRecordOfTheApprovedRouteTemplate() {
        assertTrue(matcher.matches("http://localhost:8080/projects/1/board", "Crear tarea"));
        assertTrue(matcher.matches("http://localhost:8080/projects/3f2b8c1e-1d2a-4b3c-9d4e-5f6a7b8c9d0e/board?tab=2#top", "Crear tarea"));
        assertTrue(matcher.matches("/projects/42/board/", "Crear tarea"));
        assertTrue(matcher.matches("http://localhost:8080/projects", "Crear proyecto"));
    }

    @Test
    void comparesNamesIgnoringCaseExtraWhitespaceAndUnicodeComposition() {
        assertTrue(matcher.matches("/projects/7/board", "  CREAR \n tarea "));
        ApprovedOpenerMatcher accented = new ApprovedOpenerMatcher(List.of(new ApprovedOpenerRule("/", "Añadir")));
        assertTrue(accented.matches("http://localhost/", "Añadir"));
    }

    @Test
    void rejectsOtherRoutesOtherNamesAndUnusableInput() {
        assertFalse(matcher.matches("/projects/7/board", "Eliminar tarea"));
        assertFalse(matcher.matches("/projects/7/board/settings", "Crear tarea"));
        assertFalse(matcher.matches("/projects/abc/board", "Crear tarea"));
        assertFalse(matcher.matches("/projects/7", "Crear proyecto"));
        assertFalse(matcher.matches("/projects", "Crear tarea"));
        assertFalse(matcher.matches(null, "Crear proyecto"));
        assertFalse(matcher.matches("/projects", null));
        assertFalse(matcher.matches("http://[bad", "Crear proyecto"));
        assertFalse(new ApprovedOpenerMatcher(List.of()).matches("/projects", "Crear proyecto"));
    }
}
