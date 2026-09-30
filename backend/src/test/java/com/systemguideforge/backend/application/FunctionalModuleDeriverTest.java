package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Document;
import com.systemguideforge.backend.persistence.Page;
import com.systemguideforge.backend.persistence.PageKind;
import com.systemguideforge.backend.persistence.UIElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionalModuleDeriverTest {
    private final FunctionalModuleDeriver deriver = new FunctionalModuleDeriver();

    @Test
    void placesTheLoginPageModuleFirstRegardlessOfItsUrlAlphabeticalPosition() {
        Page login = new Page("analysis-1", "http://localhost/zlogin", "Sign in", PageKind.LOGIN);
        Page admin = new Page("analysis-1", "http://localhost/admin", "Admin");

        var modules = deriver.derive(List.of(admin, login));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::key)
                .containsExactly("sgf/login", "admin");
        assertThat(modules.get(0).name()).isEqualTo("Login");
        assertThat(modules.get(0).pages()).extracting(FunctionalModuleDeriver.ModulePage::id)
                .containsExactly(login.getId());
        assertThat(modules.get(0).pages()).extracting(FunctionalModuleDeriver.ModulePage::kind)
                .containsExactly(PageKind.LOGIN);
        assertThat(modules.get(1).pages()).extracting(FunctionalModuleDeriver.ModulePage::kind)
                .containsExactly((PageKind) null);
    }

    @Test
    void keepsTheLoginModuleKeyCollisionFreeFromAnOrdinaryPageWhoseFirstPathSegmentIsLogin() {
        Page login = new Page("analysis-1", "http://localhost/login-target", "Sign in", PageKind.LOGIN);
        Page spaLandingSharingLoginPath = new Page("analysis-1", "http://localhost/login/welcome", "Welcome");

        var modules = deriver.derive(List.of(spaLandingSharingLoginPath, login));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::key)
                .containsExactly("sgf/login", "login");
        assertThat(modules).extracting(FunctionalModuleDeriver.Module::key).doesNotHaveDuplicates();
        assertThat(modules.get(0).name()).isEqualTo("Login");
        assertThat(modules.get(1).name()).isEqualTo("Login");
        assertThat(modules.get(0).pages()).extracting(FunctionalModuleDeriver.ModulePage::id)
                .containsExactly(login.getId());
        assertThat(modules.get(1).pages()).extracting(FunctionalModuleDeriver.ModulePage::id)
                .containsExactly(spaLandingSharingLoginPath.getId());
    }

    @Test
    void groupsPagesByFirstPathSegmentWithDeterministicOrderingAndNames() {
        Page users = new Page("analysis-1", "http://localhost/users/list?sort=name#top", "Users list");
        Page home = new Page("analysis-1", "http://localhost/?tab=home", "Home");
        Page settings = new Page("analysis-1", "http://localhost/admin_settings/profile", "Profile");
        Page usersRoot = new Page("analysis-1", "http://localhost/users", "Users");

        var modules = deriver.derive(List.of(users, home, settings, usersRoot));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::key)
                .containsExactly("admin_settings", "home", "users");
        assertThat(modules.get(0).name()).isEqualTo("Admin Settings");
        assertThat(modules.get(1).name()).isEqualTo("Home");
        assertThat(modules.get(2).pages()).extracting(FunctionalModuleDeriver.ModulePage::url)
                .containsExactly("http://localhost/users", "http://localhost/users/list?sort=name#top");
        assertThat(modules.get(2).pages()).extracting(FunctionalModuleDeriver.ModulePage::id)
                .containsExactly(usersRoot.getId(), users.getId());
    }

    @Test
    void treatsEmptyAndRootPathsAsHomeAndNormalizesDisplaySeparators() {
        Page root = new Page("analysis-1", "http://localhost", "Root");
        Page reports = new Page("analysis-1", "http://localhost/reports_monthly/summary", "Reports");

        var modules = deriver.derive(List.of(reports, root));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::name)
                .containsExactly("Home", "Reports Monthly");
        assertThat(modules.get(0).pages()).extracting(FunctionalModuleDeriver.ModulePage::analysisId)
                .containsOnly("analysis-1");
    }

    @Test
    void replacesNumericAndUuidPathSegmentsWithAnIdPlaceholderInRouteTemplates() {
        assertThat(deriver.routeTemplateFor("http://localhost/projects/1/board")).isEqualTo("/projects/{id}/board");
        assertThat(deriver.routeTemplateFor("http://localhost/projects/3/board?tab=x#top")).isEqualTo("/projects/{id}/board");
        assertThat(deriver.routeTemplateFor("http://localhost/items/123e4567-e89b-12d3-a456-426614174000")).isEqualTo("/items/{id}");
        assertThat(deriver.routeTemplateFor("http://localhost/items/123E4567-E89B-12D3-A456-426614174000/edit")).isEqualTo("/items/{id}/edit");
        assertThat(deriver.routeTemplateFor("http://localhost/orgs/7/projects/42")).isEqualTo("/orgs/{id}/projects/{id}");
    }

    @Test
    void keepsNonIdSegmentsRootAndEmptyPathsLiteralInRouteTemplates() {
        assertThat(deriver.routeTemplateFor("http://localhost/projects/new")).isEqualTo("/projects/new");
        assertThat(deriver.routeTemplateFor("http://localhost/v2/items")).isEqualTo("/v2/items");
        assertThat(deriver.routeTemplateFor("http://localhost/a1/12x")).isEqualTo("/a1/12x");
        assertThat(deriver.routeTemplateFor("http://localhost/")).isEqualTo("/");
        assertThat(deriver.routeTemplateFor("http://localhost")).isEqualTo("/");
    }

    @Test
    void exposesThePageHeadingOnModulePages() {
        Page page = new Page("analysis-1", "http://localhost/projects", "FlowPilot", "Projects", null);

        var modules = deriver.derive(List.of(page));

        assertThat(modules.get(0).pages()).extracting(FunctionalModuleDeriver.ModulePage::heading)
                .containsExactly("Projects");
    }

    private static UIElement link(Page page, String name, String targetPath) {
        return new UIElement(page.getId(), "a", "a:nth-of-type(1)", name, ActionClassification.SAFE, targetPath);
    }

    @Test
    void namesAModuleAfterTheAppNavigationLinkPointingToItsRootRoute() {
        Page home = new Page("analysis-1", "http://localhost/dashboard", "Dashboard");
        Page projects = new Page("analysis-1", "http://localhost/projects", "Projects");

        var modules = deriver.derive(List.of(home, projects), List.of(link(home, "Proyectos", "/projects"), link(home, "Panel", "/dashboard")));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::name).containsExactly("Panel", "Proyectos");
    }

    @Test
    void fallsBackToTheUrlDerivedNameWhenNoUsableLinkPointsToTheModule() {
        Page home = new Page("analysis-1", "http://localhost/dashboard", "Dashboard");
        Page reports = new Page("analysis-1", "http://localhost/monthly-reports", "Reports");
        Page projects = new Page("analysis-1", "http://localhost/projects", "Projects");

        var modules = deriver.derive(List.of(home, reports, projects), List.of(
                link(home, "[redacted] menu", "/monthly-reports"),
                link(home, "   ", "/projects"),
                link(home, "Deep", "/projects/1/board"),
                link(home, "Brand", "/")));

        assertThat(modules).extracting(FunctionalModuleDeriver.Module::name).containsExactly("Dashboard", "Monthly Reports", "Projects");
    }

    @Test
    void picksTheMostFrequentLinkLabelAndBreaksTiesAlphabetically() {
        Page page = new Page("analysis-1", "http://localhost/projects", "Projects");
        Page other = new Page("analysis-1", "http://localhost/tasks", "Tasks");

        var labels = deriver.navigationLabels(List.of(
                link(page, "Proyectos", "/projects"), link(other, "Mis proyectos", "/projects/"), link(page, "Mis proyectos", "/projects"),
                link(page, "Tareas B", "/tasks"), link(page, "Tareas A", "/tasks")));

        assertThat(labels).containsEntry("projects", "Mis proyectos").containsEntry("tasks", "Tareas A");
    }

    @Test
    void ignoresNonLinkElementsAndLinksWithoutATargetPath() {
        Page page = new Page("analysis-1", "http://localhost/projects", "Projects");

        var labels = deriver.navigationLabels(List.of(
                new UIElement(page.getId(), "button", "#b", "Botón", ActionClassification.SAFE, "/projects"),
                link(page, "Sin destino", null)));

        assertThat(labels).isEmpty();
    }

    @Test
    void translatesTheFixedSgfNamesOnlyWhenTheDocumentLanguageIsKnown() {
        var none = java.util.Map.<String, String>of();

        assertThat(deriver.moduleNameFor("http://localhost/", none, Document.DocumentLanguage.ES)).isEqualTo("Inicio");
        assertThat(deriver.moduleNameFor("http://localhost/", none, Document.DocumentLanguage.EN)).isEqualTo("Home");
        assertThat(deriver.moduleNameFor("http://localhost/", none, null)).isEqualTo("Home");
        assertThat(deriver.loginModuleName(Document.DocumentLanguage.ES)).isEqualTo("Ingreso al sistema");
        assertThat(deriver.loginModuleName(Document.DocumentLanguage.EN)).isEqualTo("Sign in");
        assertThat(deriver.loginModuleName(null)).isEqualTo("Login");
        assertThat(deriver.moduleNameFor("http://localhost/projects/1", java.util.Map.of("projects", "Proyectos"), Document.DocumentLanguage.ES)).isEqualTo("Proyectos");
    }
}
