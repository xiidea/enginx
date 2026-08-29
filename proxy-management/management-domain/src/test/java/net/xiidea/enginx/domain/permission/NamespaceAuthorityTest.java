package net.xiidea.enginx.domain.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that stops a wildcard grant being used to escalate.
 *
 * <p>Closes the second half of R5, which was implemented but never tested — the worst state for an
 * authorization rule to be in, because nothing would report it if a refactor quietly widened it.
 * Conferring authority over {@code *.example.com} is only legitimate for someone who already holds
 * authority over a namespace containing it; otherwise anyone with ADMIN over a single subdomain
 * could grant themselves the parent.
 */
class NamespaceAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private final PermissionEvaluationService evaluator = new PermissionEvaluationService();

    private static PermissionGrant patternGrant(String pattern, PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "ada",
                ScopeType.DOMAIN_PATTERN, null, null, DomainPattern.of(pattern), level,
                "root", NOW, null);
    }

    private static PermissionGrant globalGrant(PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "ada",
                ScopeType.GLOBAL, null, null, null, level, "root", NOW, null);
    }

    private static PermissionGrant groupGrant(PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "ada",
                ScopeType.DOMAIN_GROUP, UUID.randomUUID(), null, null, level, "root", NOW, null);
    }

    private static AuthenticatedSubject operator() {
        return new AuthenticatedSubject("sub-ada", Set.of(), Set.of(GlobalRole.OPERATOR));
    }

    private PermissionLevel authorityOver(String namespace, List<PermissionGrant> grants) {
        return evaluator.namespaceLevel(operator(), DomainPattern.of(namespace), grants, NOW);
    }

    @Nested
    @DisplayName("a pattern grant confers authority only over namespaces it contains")
    class PatternScope {

        @Test
        @DisplayName("a broader wildcard covers a narrower one")
        void broaderCoversNarrower() {
            List<PermissionGrant> held = List.of(patternGrant("*.example.com", PermissionLevel.ADMIN));

            assertThat(authorityOver("*.test.example.com", held)).isEqualTo(PermissionLevel.ADMIN);
            assertThat(authorityOver("app.example.com", held)).isEqualTo(PermissionLevel.ADMIN);
        }

        /**
         * The escalation this rule exists to prevent. Holding ADMIN over one subdomain must not
         * confer the power to grant authority over the parent — which would include every sibling.
         */
        @Test
        @DisplayName("a narrower grant confers nothing over a broader namespace")
        void narrowerDoesNotCoverBroader() {
            List<PermissionGrant> held = List.of(patternGrant("*.test.example.com", PermissionLevel.ADMIN));

            // Null, not READ: OPERATOR carries no role floor, so an inapplicable grant leaves the
            // caller with no authority at all rather than a reduced one.
            assertThat(authorityOver("*.example.com", held))
                    .describedAs("ADMIN over a subdomain must not reach the parent namespace")
                    .isNull();
        }

        @Test
        @DisplayName("an exact grant does not confer authority over the wildcard of the same name")
        void exactDoesNotCoverWildcard() {
            List<PermissionGrant> held = List.of(patternGrant("example.com", PermissionLevel.ADMIN));

            // *.example.com admits app.example.com, which example.com does not.
            assertThat(authorityOver("*.example.com", held)).isNull();
        }

        @Test
        @DisplayName("an unrelated namespace confers nothing")
        void unrelatedConfersNothing() {
            List<PermissionGrant> held = List.of(patternGrant("*.other.com", PermissionLevel.ADMIN));

            assertThat(authorityOver("*.example.com", held)).isNull();
        }
    }

    @Nested
    @DisplayName("other scope types")
    class OtherScopes {

        @Test
        @DisplayName("a global grant confers authority over every namespace")
        void globalCoversEverything() {
            assertThat(authorityOver("*.example.com", List.of(globalGrant(PermissionLevel.ADMIN))))
                    .isEqualTo(PermissionLevel.ADMIN);
        }

        /**
         * A group is a set of sites chosen by hand; it says nothing about a namespace, and the
         * sites in it may be renamed or removed. Letting it confer namespace authority would make
         * the reach of a pattern grant depend on group membership at some past moment.
         */
        @Test
        @DisplayName("a group grant confers no authority over a namespace")
        void groupConfersNothingOverANamespace() {
            assertThat(authorityOver("*.example.com", List.of(groupGrant(PermissionLevel.ADMIN))))
                    .isNull();
        }
    }

    @Test
    @DisplayName("an expired grant confers nothing")
    void expiredGrantConfersNothing() {
        PermissionGrant expired = new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "ada",
                ScopeType.DOMAIN_PATTERN, null, null, DomainPattern.of("*.example.com"),
                PermissionLevel.ADMIN, "root", NOW.minusSeconds(3600), NOW.minusSeconds(1));

        assertThat(authorityOver("*.example.com", List.of(expired))).isNull();
    }

    @Test
    @DisplayName("a role ceiling clamps the result, so a READ_ONLY account can never reach ADMIN")
    void roleCeilingClamps() {
        AuthenticatedSubject readOnly =
                new AuthenticatedSubject("sub-ada", Set.of(), Set.of(GlobalRole.READ_ONLY));

        PermissionLevel level = evaluator.namespaceLevel(readOnly, DomainPattern.of("*.example.com"),
                List.of(patternGrant("*.example.com", PermissionLevel.ADMIN)), NOW);

        assertThat(level).isEqualTo(PermissionLevel.READ);
    }
}
