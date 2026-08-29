package net.xiidea.enginx.domain.permission;

import net.xiidea.enginx.domain.group.GroupHierarchy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final UUID SITE = UUID.randomUUID();
    private static final UUID OTHER_SITE = UUID.randomUUID();
    private static final UUID PRODUCTION = UUID.randomUUID();
    private static final UUID PRODUCTION_EU = UUID.randomUUID();
    private static final UUID TESTING = UUID.randomUUID();

    private final PermissionEvaluationService evaluator = new PermissionEvaluationService();

    // app.example.com, filed under production.eu whose ancestor is production
    private static final SiteAuthorizationContext APP =
            new SiteAuthorizationContext(SITE, "com.example.app", Set.of(PRODUCTION_EU, PRODUCTION));

    @Nested
    @DisplayName("realm roles")
    class Roles {

        @Test
        void superAdminReachesEverythingWithoutAnyGrant() {
            assertThat(level(subject(GlobalRole.SUPER_ADMIN), APP)).isEqualTo(PermissionLevel.ADMIN);
        }

        @Test
        void adminGetsManageEverywhereButNotAdmin() {
            assertThat(level(subject(GlobalRole.ADMIN), APP)).isEqualTo(PermissionLevel.MANAGE);
        }

        @Test
        @DisplayName("an operator with no grant reaches nothing at all")
        void operatorWithoutGrantsHasNoAccess() {
            assertThat(level(subject(GlobalRole.OPERATOR), APP)).isNull();
        }

        @Test
        @DisplayName("READ_ONLY confers nothing on its own; it caps what a grant can reach")
        void readOnlyIsNotAGlobalReader() {
            assertThat(level(subject(GlobalRole.READ_ONLY), APP)).isNull();
        }

        @Test
        @DisplayName("READ_ONLY is a ceiling, not a default: a MANAGE grant is clamped to READ")
        void readOnlyClampsAGrant() {
            PermissionGrant manage = siteGrant(SITE, PermissionLevel.MANAGE);
            assertThat(level(subject(GlobalRole.READ_ONLY), APP, manage)).isEqualTo(PermissionLevel.READ);
        }

        @Test
        @DisplayName("holding an uncapped role alongside READ_ONLY removes the cap")
        void anUncappedRoleWinsOverTheCap() {
            AuthenticatedSubject both = new AuthenticatedSubject("u", Set.of(),
                    Set.of(GlobalRole.READ_ONLY, GlobalRole.OPERATOR));
            assertThat(level(both, APP, siteGrant(SITE, PermissionLevel.MANAGE))).isEqualTo(PermissionLevel.MANAGE);
        }
    }

    @Nested
    @DisplayName("scope matching")
    class Scopes {

        @Test
        void siteGrantReachesOnlyThatSite() {
            PermissionGrant grant = siteGrant(SITE, PermissionLevel.OPERATE);
            assertThat(level(operator(), APP, grant)).isEqualTo(PermissionLevel.OPERATE);

            SiteAuthorizationContext other = new SiteAuthorizationContext(OTHER_SITE, "com.example.api", Set.of());
            assertThat(level(operator(), other, grant)).isNull();
        }

        @Test
        @DisplayName("a grant on a parent group reaches a site filed under a child")
        void groupGrantIsInherited() {
            PermissionGrant grant = groupGrant(PRODUCTION, PermissionLevel.MANAGE);
            assertThat(level(operator(), APP, grant)).isEqualTo(PermissionLevel.MANAGE);
        }

        @Test
        void groupGrantDoesNotReachASiblingTree() {
            PermissionGrant grant = groupGrant(TESTING, PermissionLevel.MANAGE);
            assertThat(level(operator(), APP, grant)).isNull();
        }

        @Test
        void globalGrantReachesEverything() {
            PermissionGrant grant = new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u",
                    ScopeType.GLOBAL, null, null, null, PermissionLevel.READ, "admin", NOW, null);
            SiteAuthorizationContext anywhere = new SiteAuthorizationContext(OTHER_SITE, "org.somewhere.else", Set.of());
            assertThat(level(operator(), anywhere, grant)).isEqualTo(PermissionLevel.READ);
        }
    }

    @Nested
    @DisplayName("wildcard patterns")
    class Wildcards {

        @Test
        void wildcardMatchesASubdomain() {
            PermissionGrant grant = patternGrant("*.test.example.com", PermissionLevel.MANAGE);
            SiteAuthorizationContext site = context("com.example.test.app");
            assertThat(level(operator(), site, grant)).isEqualTo(PermissionLevel.MANAGE);
        }

        @Test
        @DisplayName("a wildcard does not match its own apex")
        void wildcardExcludesTheApex() {
            PermissionGrant grant = patternGrant("*.test.example.com", PermissionLevel.MANAGE);
            assertThat(level(operator(), context("com.example.test"), grant)).isNull();
        }

        @Test
        @DisplayName("a wildcard does not leak sideways into a similarly spelled domain")
        void wildcardDoesNotMatchASiblingPrefix() {
            PermissionGrant grant = patternGrant("*.test.example.com", PermissionLevel.MANAGE);
            // testing.example.com reverses to com.example.testing, which shares a prefix with
            // com.example.test but must not match it.
            assertThat(level(operator(), context("com.example.testing"), grant)).isNull();
        }

        @Test
        void exactPatternMatchesOnlyThatDomain() {
            PermissionGrant grant = patternGrant("app.example.com", PermissionLevel.OPERATE);
            assertThat(level(operator(), context("com.example.app"), grant)).isEqualTo(PermissionLevel.OPERATE);
            assertThat(level(operator(), context("com.example.app.deep"), grant)).isNull();
        }
    }

    @Nested
    @DisplayName("combining and expiry")
    class Combination {

        @Test
        @DisplayName("the highest applicable grant wins, whatever order they arrive in")
        void maximumWins() {
            List<PermissionGrant> grants = List.of(
                    siteGrant(SITE, PermissionLevel.READ),
                    groupGrant(PRODUCTION, PermissionLevel.MANAGE),
                    patternGrant("*.example.com", PermissionLevel.OPERATE));

            assertThat(evaluator.effectiveLevel(operator(), APP, grants, NOW)).isEqualTo(PermissionLevel.MANAGE);
            assertThat(evaluator.effectiveLevel(operator(), APP, grants.reversed(), NOW))
                    .isEqualTo(PermissionLevel.MANAGE);
        }

        @Test
        void anExpiredGrantConfersNothing() {
            PermissionGrant expired = new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u",
                    ScopeType.SITE, null, SITE, null, PermissionLevel.MANAGE, "admin",
                    NOW.minusSeconds(7200), NOW.minusSeconds(60));
            assertThat(level(operator(), APP, expired)).isNull();
        }

        @Test
        @DisplayName("a grant addressed to a group the caller belongs to applies to them")
        void groupSubjectGrantsApply() {
            AuthenticatedSubject member = new AuthenticatedSubject("u",
                    Set.of("/platform/production"), Set.of(GlobalRole.OPERATOR));
            PermissionGrant grant = new PermissionGrant(UUID.randomUUID(), SubjectType.GROUP,
                    "/platform/production", ScopeType.DOMAIN_GROUP, PRODUCTION, null, null,
                    PermissionLevel.OPERATE, "admin", NOW, null);

            assertThat(member.subjectRefs()).contains("/platform/production");
            assertThat(level(member, APP, grant)).isEqualTo(PermissionLevel.OPERATE);
        }
    }

    @Nested
    @DisplayName("creating a new site")
    class Creation {

        @Test
        @DisplayName("a group grant cannot authorise creating a domain, or MANAGE on any group would mean MANAGE on the namespace")
        void groupGrantDoesNotAuthoriseCreation() {
            assertThat(evaluator.canCreate(operator(), "com.example.brandnew",
                    List.of(groupGrant(PRODUCTION, PermissionLevel.MANAGE)), NOW)).isFalse();
        }

        @Test
        void patternGrantAuthorisesCreationWithinItsNamespace() {
            List<PermissionGrant> grants = List.of(patternGrant("*.test.example.com", PermissionLevel.MANAGE));
            assertThat(evaluator.canCreate(operator(), "com.example.test.new", grants, NOW)).isTrue();
            assertThat(evaluator.canCreate(operator(), "com.example.prod.new", grants, NOW)).isFalse();
        }

        @Test
        void operateIsNotEnoughToCreate() {
            assertThat(evaluator.canCreate(operator(), "com.example.test.new",
                    List.of(patternGrant("*.test.example.com", PermissionLevel.OPERATE)), NOW)).isFalse();
        }
    }

    @Nested
    @DisplayName("accessible scope for listings")
    class Scope {

        private final GroupHierarchy hierarchy = new GroupHierarchy() {
            @Override
            public Set<UUID> descendantIdsOf(Collection<UUID> groupIds) {
                return groupIds.contains(PRODUCTION) ? Set.of(PRODUCTION, PRODUCTION_EU) : Set.copyOf(groupIds);
            }

            @Override
            public Set<UUID> ancestorIdsOf(Collection<UUID> groupIds) {
                return Set.copyOf(groupIds);
            }

            @Override
            public Set<UUID> groupIdsForSite(UUID siteId) {
                return Set.of();
            }
        };

        @Test
        void superAdminIsUnrestricted() {
            AccessScope scope = evaluator.accessibleScope(subject(GlobalRole.SUPER_ADMIN), List.of(),
                    PermissionLevel.READ, NOW, hierarchy);
            assertThat(scope.unrestricted()).isTrue();
        }

        @Test
        @DisplayName("an operator with no grants sees nothing, and that is an empty page rather than an error")
        void noGrantsMeansNoRows() {
            AccessScope scope = evaluator.accessibleScope(operator(), List.of(), PermissionLevel.READ, NOW, hierarchy);
            assertThat(scope.unrestricted()).isFalse();
            assertThat(scope.deniesEverything()).isTrue();
        }

        @Test
        @DisplayName("a READ_ONLY account with no grants sees nothing, not everything")
        void readOnlyWithoutGrantsSeesNothing() {
            AccessScope scope = evaluator.accessibleScope(subject(GlobalRole.READ_ONLY), List.of(),
                    PermissionLevel.READ, NOW, hierarchy);
            assertThat(scope.unrestricted()).isFalse();
            assertThat(scope.deniesEverything()).isTrue();
        }

        @Test
        @DisplayName("READ_ONLY can never reach OPERATE, so an operate-level listing is empty")
        void readOnlyCannotReachOperate() {
            AccessScope scope = evaluator.accessibleScope(subject(GlobalRole.READ_ONLY),
                    List.of(siteGrant(SITE, PermissionLevel.MANAGE)), PermissionLevel.OPERATE, NOW, hierarchy);
            assertThat(scope.deniesEverything()).isTrue();
        }

        @Test
        void groupGrantsAreExpandedToDescendants() {
            AccessScope scope = evaluator.accessibleScope(operator(),
                    List.of(groupGrant(PRODUCTION, PermissionLevel.READ)), PermissionLevel.READ, NOW, hierarchy);
            assertThat(scope.groupIds()).containsExactlyInAnyOrder(PRODUCTION, PRODUCTION_EU);
        }

        @Test
        @DisplayName("grants below the requested level are dropped from the predicate")
        void grantsBelowTheRequestedLevelAreIgnored() {
            AccessScope scope = evaluator.accessibleScope(operator(),
                    List.of(siteGrant(SITE, PermissionLevel.READ)), PermissionLevel.MANAGE, NOW, hierarchy);
            assertThat(scope.deniesEverything()).isTrue();
        }

        @Test
        void aGlobalGrantShortCircuitsToUnrestricted() {
            PermissionGrant global = new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u",
                    ScopeType.GLOBAL, null, null, null, PermissionLevel.READ, "admin", NOW, null);
            AccessScope scope = evaluator.accessibleScope(operator(), List.of(global),
                    PermissionLevel.READ, NOW, hierarchy);
            assertThat(scope.unrestricted()).isTrue();
        }
    }

    // ---- fixtures ----

    private PermissionLevel level(AuthenticatedSubject subject, SiteAuthorizationContext site,
                                  PermissionGrant... grants) {
        return evaluator.effectiveLevel(subject, site, List.of(grants), NOW);
    }

    private static AuthenticatedSubject subject(GlobalRole role) {
        return new AuthenticatedSubject("u", Set.of(), Set.of(role));
    }

    private static AuthenticatedSubject operator() {
        return subject(GlobalRole.OPERATOR);
    }

    private static SiteAuthorizationContext context(String domainReversed) {
        return new SiteAuthorizationContext(UUID.randomUUID(), domainReversed, Set.of());
    }

    private static PermissionGrant siteGrant(UUID siteId, PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u", ScopeType.SITE,
                null, siteId, null, level, "admin", NOW, null);
    }

    private static PermissionGrant groupGrant(UUID groupId, PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u", ScopeType.DOMAIN_GROUP,
                groupId, null, null, level, "admin", NOW, null);
    }

    private static PermissionGrant patternGrant(String pattern, PermissionLevel level) {
        return new PermissionGrant(UUID.randomUUID(), SubjectType.USER, "u", ScopeType.DOMAIN_PATTERN,
                null, null, DomainPattern.of(pattern), level, "admin", NOW, null);
    }
}
