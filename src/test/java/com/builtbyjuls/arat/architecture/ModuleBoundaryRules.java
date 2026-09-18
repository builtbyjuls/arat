package com.builtbyjuls.arat.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.library.dependencies.Slice;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

final class ModuleBoundaryRules {

    private static final Set<String> TECHNICAL_SUPPORT_PACKAGES = Set.of("platform", "web");

    private ModuleBoundaryRules() {
    }

    static ArchRule businessModulesUseOnlyOtherModuleApis(String rootPackage) {
        return classes().that(new DescribedPredicate<JavaClass>("belong to business modules") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return businessModuleName(javaClass, rootPackage).isPresent();
                    }
                })
                .should(new ArchCondition<JavaClass>("depend on other business modules only through their api packages") {
                    @Override
                    public void check(JavaClass source, ConditionEvents events) {
                        var sourceModule = businessModuleName(source, rootPackage).orElseThrow();
                        source.getDirectDependenciesFromSelf().forEach(dependency ->
                                reportCrossModuleInternalDependency(source, sourceModule, dependency, rootPackage, events));
                    }
                });
    }

    static ArchRule platformIsIndependentOfBusinessModules(String rootPackage) {
        return classes().that(new DescribedPredicate<JavaClass>("belong to technical support packages") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return technicalSupportPackageName(javaClass, rootPackage).isPresent();
                    }
                })
                .should(new ArchCondition<JavaClass>("not depend on business modules") {
                    @Override
                    public void check(JavaClass source, ConditionEvents events) {
                        source.getDirectDependenciesFromSelf().forEach(dependency -> {
                            if (businessModuleName(dependency.getTargetClass(), rootPackage).isPresent()) {
                                events.add(SimpleConditionEvent.violated(
                                        source,
                                        source.getFullName() + " depends on business module "
                                                + dependency.getTargetClass().getFullName()));
                            }
                        });
                    }
                });
    }

    static ArchRule businessModuleSlicesAreCycleFree(String rootPackage) {
        return slices().matching(rootPackage + ".(*)..")
                .that(new DescribedPredicate<Slice>("belong to business modules") {
                    @Override
                    public boolean test(Slice slice) {
                        return !TECHNICAL_SUPPORT_PACKAGES.contains(slice.getNamePart(1));
                    }
                })
                .should().beFreeOfCycles();
    }

    private static void reportCrossModuleInternalDependency(
            JavaClass source,
            String sourceModule,
            Dependency dependency,
            String rootPackage,
            ConditionEvents events) {
        var target = dependency.getTargetClass();
        var targetModule = businessModuleName(target, rootPackage);
        if (targetModule.isPresent()
                && !sourceModule.equals(targetModule.get())
                && !isPublicApiPackage(target.getPackageName(), rootPackage, targetModule.get())) {
            events.add(SimpleConditionEvent.violated(
                    source,
                    source.getFullName() + " depends on internal package " + target.getFullName()));
        }
    }

    private static boolean isPublicApiPackage(String packageName, String rootPackage, String moduleName) {
        var apiPackage = rootPackage + "." + moduleName + ".api";
        return packageName.equals(apiPackage) || packageName.startsWith(apiPackage + ".");
    }

    private static Optional<String> businessModuleName(JavaClass javaClass, String rootPackage) {
        return directChildPackageName(javaClass, rootPackage)
                .filter(packageName -> !TECHNICAL_SUPPORT_PACKAGES.contains(packageName));
    }

    private static Optional<String> technicalSupportPackageName(JavaClass javaClass, String rootPackage) {
        return directChildPackageName(javaClass, rootPackage)
                .filter(TECHNICAL_SUPPORT_PACKAGES::contains);
    }

    private static Optional<String> directChildPackageName(JavaClass javaClass, String rootPackage) {
        var packageName = javaClass.getPackageName();
        var prefix = rootPackage + ".";
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        var remainder = packageName.substring(prefix.length());
        var separator = remainder.indexOf('.');
        return Optional.of(separator == -1 ? remainder : remainder.substring(0, separator));
    }
}
