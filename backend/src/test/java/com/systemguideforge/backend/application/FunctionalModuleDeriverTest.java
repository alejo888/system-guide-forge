package com.systemguideforge.backend.application;

import com.systemguideforge.backend.persistence.Page;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionalModuleDeriverTest {
    private final FunctionalModuleDeriver deriver = new FunctionalModuleDeriver();

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
}
