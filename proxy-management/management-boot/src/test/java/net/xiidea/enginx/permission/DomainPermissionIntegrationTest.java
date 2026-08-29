package net.xiidea.enginx.permission;

import net.xiidea.enginx.application.group.DomainGroupCommands;
import net.xiidea.enginx.application.group.DomainGroupService;
import net.xiidea.enginx.application.permission.PermissionCommands;
import net.xiidea.enginx.application.permission.PermissionGrantService;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The domain-level permission model, exercised end to end against real PostgreSQL.
 *
 * <p>These cover the scenarios the brief names directly — an unauthorised user cannot reach
 * another domain, a read-only user cannot modify anything — plus the escalation paths that a
 * scoped permission system invites and that unit tests alone would not catch, because they
 * depend on the SQL that filters listings.
 */
@Import(TestSubjectProvider.Config.class)
class DomainPermissionIntegrationTest extends AbstractIntegrationTest {

    private static final String ALICE = "user-alice";
    private static final String BOB = "user-bob";
    private static final String PLATFORM_TEAM = "/platform/production";

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private DomainGroupService groups;
    @Autowired
    private PermissionGrantService permissions;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private ProxySite appSite;
    private ProxySite apiSite;
    private ProxySite testSite;
    private DomainGroup production;
    private DomainGroup productionEu;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate permission_grants, domain_group_members, domain_groups, proxy_sites, "
                + "nginx_instances restart identity cascade");

        caller.actAsSuperAdmin();

        NginxInstance instance = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-permissions",
                "nginx", "https://nginx:8443", "A".repeat(64), "TEST", Instant.now()));
        instanceId = instance.id();

        appSite = createSite("app.example.com");
        apiSite = createSite("api.example.com");
        testSite = createSite("checkout.test.example.com");

        production = groups.create(new DomainGroupCommands.Create("Production", "production", null, null));
        productionEu = groups.create(new DomainGroupCommands.Create("Production EU", "eu", null, production.id()));
        groups.addMember(productionEu.id(), appSite.id());
    }

    @Nested
    @DisplayName("a user reaches only what they were granted")
    class Isolation {

        @Test
        @DisplayName("a site grant does not leak into a neighbouring domain")
        void grantOnOneSiteDoesNotReachAnother() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            assertThat(sites.get(appSite.id()).domain().value()).isEqualTo("app.example.com");

            // Not 403: telling Alice "forbidden" would confirm api.example.com exists, which is
            // itself information she has not been granted.
            assertThatThrownBy(() -> sites.get(apiSite.id())).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("the listing shows only granted sites, and the total does not betray the rest")
        void listingIsFilteredInTheQuery() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.READ);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            PageResult<ProxySite> page = sites.search(ProxySiteQuery.firstPage());

            assertThat(page.content()).extracting(site -> site.domain().value())
                    .containsExactly("app.example.com");
            assertThat(page.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("a user with no grants sees an empty page rather than an error")
        void noGrantsYieldsAnEmptyPage() {
            caller.actAs(BOB, Set.of(), GlobalRole.OPERATOR);

            PageResult<ProxySite> page = sites.search(ProxySiteQuery.firstPage());

            assertThat(page.content()).isEmpty();
            assertThat(page.totalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("read-only accounts")
    class ReadOnly {

        @Test
        @DisplayName("a READ_ONLY user cannot modify a site even when granted MANAGE")
        void readOnlyCannotModify() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.READ_ONLY);

            assertThat(sites.get(appSite.id())).isNotNull();

            assertThatThrownBy(() -> sites.disable(appSite.id()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> sites.delete(appSite.id(), null))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a READ_ONLY user's operate-level listing is empty, so the console offers nothing")
        void readOnlyHasNoOperableSites() {
            grantToUser(ALICE, ScopeType.GLOBAL, null, null, null, PermissionLevel.ADMIN);
            caller.actAs(ALICE, Set.of(), GlobalRole.READ_ONLY);

            assertThat(sites.search(ProxySiteQuery.firstPage()).totalElements()).isEqualTo(3);
            assertThatThrownBy(() -> sites.enable(appSite.id())).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("domain groups")
    class Groups {

        @Test
        @DisplayName("a grant on a parent group reaches a site filed under a child")
        void grantIsInheritedDownTheTree() {
            grantToUser(ALICE, ScopeType.DOMAIN_GROUP, production.id(), null, null, PermissionLevel.OPERATE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            ProxySite disabled = sites.disable(appSite.id());
            assertThat(disabled.adminState()).isEqualTo(AdminState.DISABLED);

            // api.example.com is in no group, so the same grant does not reach it.
            assertThatThrownBy(() -> sites.disable(apiSite.id())).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("the listing resolves group membership through the hierarchy in SQL")
        void listingResolvesGroupHierarchy() {
            groups.addMember(productionEu.id(), apiSite.id());
            grantToUser(ALICE, ScopeType.DOMAIN_GROUP, production.id(), null, null, PermissionLevel.READ);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            assertThat(sites.search(ProxySiteQuery.firstPage()).content())
                    .extracting(site -> site.domain().value())
                    .containsExactlyInAnyOrder("app.example.com", "api.example.com");
        }

        @Test
        @DisplayName("a grant addressed to a Keycloak group applies to its members")
        void groupSubjectGrantsApply() {
            grantToGroup(PLATFORM_TEAM, ScopeType.DOMAIN_GROUP, production.id(), PermissionLevel.MANAGE);
            caller.actAs(BOB, Set.of(PLATFORM_TEAM), GlobalRole.OPERATOR);

            assertThat(sites.get(appSite.id())).isNotNull();

            // Leaving the group removes the access with the next token, no sync required.
            caller.actAs(BOB, Set.of(), GlobalRole.OPERATOR);
            assertThatThrownBy(() -> sites.get(appSite.id())).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("adding a site to a group needs authority over the site too, not just the group")
        void membershipCannotBeUsedToCaptureASite() {
            grantToUser(ALICE, ScopeType.DOMAIN_GROUP, production.id(), null, null, PermissionLevel.ADMIN);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            // Alice fully controls the group, but not api.example.com. If she could file it into
            // her own group she would inherit MANAGE over it through her own grant.
            assertThatThrownBy(() -> groups.addMember(production.id(), apiSite.id()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("wildcard patterns")
    class Wildcards {

        @Test
        @DisplayName("a wildcard grant filters the listing by reversed-domain prefix in SQL")
        void wildcardFiltersTheListing() {
            grantToUser(ALICE, ScopeType.DOMAIN_PATTERN, null, null, "*.test.example.com", PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            assertThat(sites.search(ProxySiteQuery.firstPage()).content())
                    .extracting(site -> site.domain().value())
                    .containsExactly("checkout.test.example.com");
        }

        @Test
        @DisplayName("a wildcard grant authorises creating inside its namespace but not outside")
        void wildcardBoundsCreation() {
            grantToUser(ALICE, ScopeType.DOMAIN_PATTERN, null, null, "*.test.example.com", PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            ProxySite created = sites.create(new ProxySiteCommands.Create(
                    specFor("cart.test.example.com"), AdminState.ENABLED));
            assertThat(created.domain().value()).isEqualTo("cart.test.example.com");

            assertThatThrownBy(() -> sites.create(new ProxySiteCommands.Create(
                    specFor("payments.example.com"), AdminState.ENABLED)))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a group grant alone cannot create a domain, which would otherwise capture the namespace")
        void groupGrantCannotCreate() {
            grantToUser(ALICE, ScopeType.DOMAIN_GROUP, production.id(), null, null, PermissionLevel.ADMIN);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            assertThatThrownBy(() -> sites.create(new ProxySiteCommands.Create(
                    specFor("anything.example.com"), AdminState.ENABLED)))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("delegation")
    class Delegation {

        @Test
        @DisplayName("nobody may grant more than they hold")
        void cannotGrantAboveYourOwnLevel() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.ADMIN);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            // Alice administers one site, so she may delegate over that site.
            permissions.grant(new PermissionCommands.Grant(SubjectType.USER, BOB, ScopeType.SITE,
                    null, appSite.id(), null, PermissionLevel.OPERATE, null));

            // She may not turn that into authority over everything.
            assertThatThrownBy(() -> permissions.grant(new PermissionCommands.Grant(
                    SubjectType.USER, BOB, ScopeType.GLOBAL, null, null, null, PermissionLevel.ADMIN, null)))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("MANAGE is not enough to delegate; administering a scope is a separate level")
        void manageCannotDelegate() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            assertThatThrownBy(() -> permissions.grant(new PermissionCommands.Grant(
                    SubjectType.USER, BOB, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.READ, null)))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a pattern grant may only be widened within a namespace the grantor already holds")
        void patternDelegationIsBounded() {
            grantToUser(ALICE, ScopeType.DOMAIN_PATTERN, null, null, "*.test.example.com", PermissionLevel.ADMIN);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);

            permissions.grant(new PermissionCommands.Grant(SubjectType.USER, BOB, ScopeType.DOMAIN_PATTERN,
                    null, null, "*.inner.test.example.com", PermissionLevel.OPERATE, null));

            assertThatThrownBy(() -> permissions.grant(new PermissionCommands.Grant(
                    SubjectType.USER, BOB, ScopeType.DOMAIN_PATTERN, null, null, "*.example.com",
                    PermissionLevel.OPERATE, null)))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("read-only accounts are confined, not privileged")
    class ReadOnlyScope {

        @Test
        @DisplayName("READ_ONLY confers no access of its own: the role caps, it does not grant")
        void readOnlyWithoutGrantsSeesNothing() {
            caller.actAs(ALICE, Set.of(), GlobalRole.READ_ONLY);

            assertThat(sites.search(ProxySiteQuery.firstPage()).totalElements()).isZero();
            assertThatThrownBy(() -> sites.get(appSite.id())).isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("a READ_ONLY user with a wildcard grant sees that namespace and nothing else")
        void readOnlySeesOnlyTheGrantedNamespace() {
            grantToUser(ALICE, ScopeType.DOMAIN_PATTERN, null, null, "*.test.example.com", PermissionLevel.MANAGE);
            caller.actAs(ALICE, Set.of(), GlobalRole.READ_ONLY);

            assertThat(sites.search(ProxySiteQuery.firstPage()).content())
                    .extracting(site -> site.domain().value())
                    .containsExactly("checkout.test.example.com");
        }
    }

    @Nested
    @DisplayName("changing an existing grant")
    class Regranting {

        @Test
        @DisplayName("re-granting the same scope changes the level instead of failing on a constraint")
        void regrantingChangesTheLevel() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.READ);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);
            assertThatThrownBy(() -> sites.disable(appSite.id())).isInstanceOf(AccessDeniedException.class);

            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.OPERATE);
            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);
            assertThat(sites.disable(appSite.id()).adminState()).isEqualTo(AdminState.DISABLED);

            Integer rows = jdbc.queryForObject(
                    "select count(*) from permission_grants where subject_ref = ?", Integer.class, ALICE);
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("the level change is auditable: the previous level is kept as the before state")
        void theLevelChangeIsAudited() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.READ);
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.MANAGE);

            String before = jdbc.queryForObject(
                    "select before_state ->> 'level' from audit_logs "
                            + "where action = 'PERMISSION_GRANTED' and before_state is not null "
                            + "order by occurred_at desc limit 1", String.class);
            assertThat(before).isEqualTo("READ");
        }

        @Test
        @DisplayName("a re-grant is still bounded by what the grantor holds")
        void regrantingCannotEscalate() {
            grantToUser(ALICE, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.ADMIN);
            grantToUser(BOB, ScopeType.SITE, null, appSite.id(), null, PermissionLevel.READ);

            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);
            // Alice may raise Bob up to her own level, but no further.
            permissions.grant(new PermissionCommands.Grant(SubjectType.USER, BOB, ScopeType.SITE,
                    null, appSite.id(), null, PermissionLevel.ADMIN, null));

            assertThatThrownBy(() -> permissions.grant(new PermissionCommands.Grant(
                    SubjectType.USER, BOB, ScopeType.GLOBAL, null, null, null, PermissionLevel.READ, null)))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("an expired grant stops conferring access without anything having to run")
        void expiredGrantsAreInert() {
            caller.actAsSuperAdmin();
            permissions.grant(new PermissionCommands.Grant(SubjectType.USER, ALICE, ScopeType.SITE,
                    null, appSite.id(), null, PermissionLevel.MANAGE,
                    Instant.now().plus(1, ChronoUnit.HOURS)));

            caller.actAs(ALICE, Set.of(), GlobalRole.OPERATOR);
            assertThat(sites.get(appSite.id())).isNotNull();

            // Move the expiry into the past directly: there is no background job to wait for,
            // because the check is a predicate evaluated on every request.
            jdbc.update("update permission_grants set expires_at = now() - interval '1 minute'");

            assertThatThrownBy(() -> sites.get(appSite.id())).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("denials are recorded")
    class Auditing {

        @Test
        void aRefusalIsAudited() {
            caller.actAs(BOB, Set.of(), GlobalRole.OPERATOR);
            assertThatThrownBy(() -> sites.get(appSite.id())).isInstanceOf(NotFoundException.class);

            Integer denials = jdbc.queryForObject(
                    "select count(*) from audit_logs where action = 'ACCESS_DENIED' and result = 'DENIED'",
                    Integer.class);
            assertThat(denials).isPositive();
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private ProxySite createSite(String domain) {
        return sites.create(new ProxySiteCommands.Create(specFor(domain), AdminState.ENABLED));
    }

    private ProxySiteSpec specFor(String domain) {
        return new ProxySiteSpec("Site " + domain, DomainName.of(domain), instanceId,
                TimeWindow.unbounded(), false, false, false, false, null,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.0.0.10", 8080)), List.of(), List.of());
    }

    private void grantToUser(String subject, ScopeType scope, UUID groupId, UUID siteId,
                             String pattern, PermissionLevel level) {
        caller.actAsSuperAdmin();
        permissions.grant(new PermissionCommands.Grant(SubjectType.USER, subject, scope,
                groupId, siteId, pattern, level, null));
    }

    private void grantToGroup(String groupPath, ScopeType scope, UUID groupId, PermissionLevel level) {
        caller.actAsSuperAdmin();
        permissions.grant(new PermissionCommands.Grant(SubjectType.GROUP, groupPath, scope,
                groupId, null, null, level, null));
    }
}
