package com.builtbyjuls.arat.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.matching.api.MatchingAccess;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.planning.api.ProviderSafeRequestSnapshot;
import com.builtbyjuls.arat.planning.api.ProviderSafeRequestTerms;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

class ModuleBoundaryRulesTest {

    private static final String APPLICATION_PACKAGE = "com.builtbyjuls.arat";
    private static final String ILLEGAL_FIXTURE_PACKAGE = APPLICATION_PACKAGE + ".architecture.fixture.illegal";
    private static final String CYCLE_FIXTURE_PACKAGE = APPLICATION_PACKAGE + ".architecture.fixture.cycle";
    private static final String MESSAGING_FIXTURE_PACKAGE = APPLICATION_PACKAGE + ".architecture.fixture.messaging";

    @Test
    void productionClassesRespectModuleBoundaries() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(APPLICATION_PACKAGE);

        ModuleBoundaryRules.businessModulesUseOnlyOtherModuleApis(APPLICATION_PACKAGE).check(classes);
        ModuleBoundaryRules.platformIsIndependentOfBusinessModules(APPLICATION_PACKAGE).check(classes);
        ModuleBoundaryRules.messagingIsIndependentOfDomainModules(APPLICATION_PACKAGE).check(classes);
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

    @Test
    void detectsMessagingDependencyOnAForbiddenPublicApi() {
        var classes = new ClassFileImporter().importPackages(MESSAGING_FIXTURE_PACKAGE);

        var result = ModuleBoundaryRules.messagingIsIndependentOfDomainModules(MESSAGING_FIXTURE_PACKAGE)
                .evaluate(classes);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("messaging.api.ForbiddenMessagingDependency")
                        && detail.contains("groups.api.GroupsApi"));
    }

    @Test
    void providerEligibilityBoundaryDoesNotExposeInfrastructureTypes() {
        assertThat(ProviderEligibilityAccess.class.getDeclaredMethods())
                .allSatisfy(this::assertDoesNotExposeInfrastructureType);
    }

    @Test
    void matchingBoundaryDoesNotExposeInfrastructureTypes() {
        assertThat(MatchingAccess.class.getDeclaredMethods())
                .allSatisfy(this::assertDoesNotExposeInfrastructureType);
    }

    @Test
    void messagingBoundaryDoesNotExposeInfrastructureTypes() {
        assertThat(MessagingAccess.class.getDeclaredMethods())
                .allSatisfy(this::assertDoesNotExposeInfrastructureType);
    }

    @Test
    void planningRequestBoundaryDoesNotExposeInternalTypes() {
        assertThat(Arrays.stream(PlanningRequestAccess.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers())))
                .allSatisfy(this::assertDoesNotExposePlanningInternalType);
    }

    @Test
    void providerSafeRequestTypesExcludePrivatePlanAndGroupFields() {
        assertThat(Arrays.stream(ProviderSafeRequestTerms.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "category", "timeZone", "areaCode", "radiusKm", "requestedStartsAt",
                        "requestedEndsAt", "minimumHeadcount", "maximumHeadcount",
                        "budgetMinimumMinorUnits", "budgetMaximumMinorUnits", "mustHaves",
                        "providerSafeNotes", "categoryAttributes", "offerDeadline");
        assertThat(Arrays.stream(ProviderSafeRequestSnapshot.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "requestId", "requestVersion", "state", "distributionMode", "category",
                        "timeZone", "areaCode", "radiusKm", "requestedStartsAt", "requestedEndsAt",
                        "minimumHeadcount", "maximumHeadcount", "budgetMinimumMinorUnits",
                        "budgetMaximumMinorUnits", "mustHaves", "providerSafeNotes",
                        "categoryAttributes", "offerDeadline", "publishedAt", "actionable");
    }

    private void assertDoesNotExposeInfrastructureType(Method method) {
        assertThat(method.getReturnType().getPackageName()).doesNotContain(".infrastructure");
        assertThat(Arrays.stream(method.getParameterTypes()).map(Class::getPackageName))
                .allSatisfy(packageName -> assertThat(packageName).doesNotContain(".infrastructure"));
    }

    private void assertDoesNotExposePlanningInternalType(Method method) {
        assertThat(method.toGenericString())
                .doesNotContain(".planning.infrastructure")
                .doesNotContain(".planning.domain");
        assertThat(method.getReturnType().getPackageName())
                .doesNotContain(".planning.infrastructure")
                .doesNotContain(".planning.domain");
        assertThat(Arrays.stream(method.getParameterTypes()).map(Class::getPackageName))
                .allSatisfy(packageName -> assertThat(packageName)
                        .doesNotContain(".planning.infrastructure")
                        .doesNotContain(".planning.domain"));
    }
}
