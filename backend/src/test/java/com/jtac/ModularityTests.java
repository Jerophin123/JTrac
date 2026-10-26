package com.jtac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the modular-monolith boundaries: no cycles between modules and no access to
 * another module's internal packages. Also renders module diagrams to
 * {@code build/spring-modulith-docs}.
 */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(JtacApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void theDocumentedModulesExist() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder("shared", "identity", "audit", "mail", "organization", "project", "field",
                        "workflow", "issue", "collaboration", "attachment", "realtime", "notification", "search",
                        "reporting", "planning", "integration", "system");
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
