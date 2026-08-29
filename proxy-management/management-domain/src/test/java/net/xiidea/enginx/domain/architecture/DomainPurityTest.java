package net.xiidea.enginx.domain.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards architecture decision AD-1.
 *
 * <p>The domain module's independence is what makes the renderer and the permission evaluator
 * testable without a container. That property is easy to lose to a single convenient import,
 * so it is asserted rather than documented.
 */
class DomainPurityTest {

    private static final JavaClasses DOMAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("net.xiidea.enginx.domain");

    /**
     * Guards every other rule in this class.
     *
     * <p>ArchUnit rules pass vacuously when nothing matches, so a package that has been renamed,
     * moved or misspelled turns the whole suite green while enforcing nothing. That is the worst
     * possible failure mode for an architecture test: it keeps reporting success precisely when it
     * has stopped looking. Asserting the import found classes is what makes the rest meaningful.
     */
    @Test
    @DisplayName("the importer actually found the domain, so the rules below are not vacuous")
    void domainClassesWereImported() {
        assertThat(DOMAIN).isNotEmpty();
        assertThat(DOMAIN.stream().map(JavaClass::getName))
                .contains("net.xiidea.enginx.domain.proxy.ProxySite");
    }

    @Test
    @DisplayName("the domain depends on no framework")
    void domainHasNoFrameworkDependencies() {
        // `javax..` is deliberately absent from this list. It used to be a reliable stand-in for
        // Java EE, but Jakarta EE moved to `jakarta..` and what remains under `javax` is JDK
        // standard library — javax.crypto, javax.net, javax.security.auth. Banning it would
        // forbid the domain from reading an X.509 certificate, which is exactly the kind of
        // framework-free logic this rule exists to protect.
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "org.hibernate..",
                        "com.fasterxml..",
                        "tools.jackson..",
                        "org.shredzone..",
                        "org.bouncycastle..",
                        "io.swagger..")
                .because("the domain must stay plain Java so it can be tested without a container (AD-1)");

        rule.check(DOMAIN);
    }

    @Test
    @DisplayName("the domain does not read the clock for itself")
    void domainDoesNotUseAmbientTime() {
        ArchRule rule = noClasses()
                .should().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .because("every lifecycle rule takes 'now' as a parameter so expiry can be tested deterministically");

        rule.check(DOMAIN);
    }
}
