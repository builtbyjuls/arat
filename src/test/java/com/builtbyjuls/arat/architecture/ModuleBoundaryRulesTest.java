package com.builtbyjuls.arat.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleBoundaryRulesTest {

    private static final String APPLICATION_PACKAGE = "com.builtbyjuls.arat";
    private static final String ILLEGAL_FIXTURE_PACKAGE = APPLICATION_PACKAGE + ".architecture.fixture.illegal";
    private static final String CYCLE_FIXTURE_PACKAGE = APPLICATION_PACKAGE + ".architecture.fixture.cycle";

    @Test
    void productionClassesRespectModuleBoundaries() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(APPLICATION_PACKAGE);

        ModuleBoundaryRules.businessModulesUseOnlyOtherModuleApis(APPLICATION_PACKAGE).check(classes);
        ModuleBoundaryRules.platformIsIndependentOfBusinessModules(APPLICATION_PACKAGE).check(classes);
        ModuleBoundaryRules.businessModuleSlicesAreCycleFree(APPLICATION_PACKAGE).check(classes);
    }

    @Test
    void detectsAnIllegalCrossModuleInternalDependency() {
        var classes = new ClassFileImporter().importPackages(ILLEGAL_FIXTURE_PACKAGE);

        var result = ModuleBoundaryRules.businessModulesUseOnlyOtherModuleApis(ILLEGAL_FIXTURE_PACKAGE)
                .evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("consumer.application.InternalConsumer")
                        && detail.contains("producer.internal.InternalProducer"));
    }

    @Test
    void detectsAnApiLookalikePackageAsAnInternalDependency() {
        var classes = new ClassFileImporter().importPackages(ILLEGAL_FIXTURE_PACKAGE);

        var result = ModuleBoundaryRules.businessModulesUseOnlyOtherModuleApis(ILLEGAL_FIXTURE_PACKAGE)
                .evaluate(classes);

        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("consumer.application.ApiLookalikeConsumer")
                        && detail.contains("producer.apiinternal.ApiLookalikeProducer"));
    }

    @Test
    void detectsABusinessModuleDependencyCycle() {
        var classes = new ClassFileImporter().importPackages(CYCLE_FIXTURE_PACKAGE);

        var result = ModuleBoundaryRules.businessModuleSlicesAreCycleFree(CYCLE_FIXTURE_PACKAGE)
                .evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("Cycle detected");
    }
}
