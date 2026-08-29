package net.xiidea.enginx.permission;

import net.xiidea.enginx.application.permission.GrantPreview;
import net.xiidea.enginx.application.permission.PermissionCommands;
import net.xiidea.enginx.application.permission.PermissionGrantService;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The grant preview, closing the first half of R5.
 *
 * <p>A wildcard grant is one line of configuration and authority over an open-ended set. The
 * preview exists so the person making it sees the set before committing, which is the difference
 * between an informed decision and a plausible-looking one.
 */
@Import(TestSubjectProvider.Config.class)
class GrantPreviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PermissionGrantService grants;
    @Autowired
    private ProxySiteService sites;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate permission_grants, domain_group_members, domain_groups, proxy_sites, "
                + "nginx_instances restart identity cascade");
        caller.actAsSuperAdmin();

        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-preview",
                "nginx", "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();

        site("app.example.com");
        site("api.example.com");
        site("checkout.test.example.com");
        site("unrelated.other.com");
    }

    private ProxySite site(String domain) {
        return sites.create(new ProxySiteCommands.Create(new ProxySiteSpec(
                domain, DomainName.of(domain), instanceId, TimeWindow.unbounded(),
                false, false, false, false, null,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.0.0.1", 8080)), List.of(), List.of()), null));
    }

    private GrantPreview preview(ScopeType scopeType, String pattern, UUID siteId) {
        return grants.preview(new PermissionCommands.Grant(SubjectType.USER, "grace",
                scopeType, null, siteId, pattern, PermissionLevel.MANAGE, null));
    }

    @Test
    @DisplayName("a wildcard shows every domain it reaches today")
    void wildcardListsWhatItReaches() {
        GrantPreview preview = preview(ScopeType.DOMAIN_PATTERN, "*.example.com", null);

        assertThat(preview.matched()).extracting(GrantPreview.MatchedSite::domain)
                .containsExactlyInAnyOrder("app.example.com", "api.example.com",
                        "checkout.test.example.com");
        assertThat(preview.totalMatched()).isEqualTo(3);
    }

    /**
     * The caveat that keeps the preview honest. The list answers "what does this reach now"; a
     * wildcard keeps admitting domains created afterwards, and a preview that did not say so
     * would be actively misleading about the thing R5 is about.
     */
    @Test
    @DisplayName("a wildcard is flagged as covering domains that do not exist yet")
    void wildcardCoversFutureDomains() {
        assertThat(preview(ScopeType.DOMAIN_PATTERN, "*.example.com", null).coversFutureDomains())
                .isTrue();
    }

    @Test
    @DisplayName("an exact pattern reaches one domain and no future ones")
    void exactPatternIsBounded() {
        GrantPreview preview = preview(ScopeType.DOMAIN_PATTERN, "app.example.com", null);

        assertThat(preview.matched()).extracting(GrantPreview.MatchedSite::domain)
                .containsExactly("app.example.com");
        assertThat(preview.coversFutureDomains()).isFalse();
    }

    @Test
    @DisplayName("a narrower wildcard does not reach its siblings")
    void narrowWildcardExcludesSiblings() {
        GrantPreview preview = preview(ScopeType.DOMAIN_PATTERN, "*.test.example.com", null);

        assertThat(preview.matched()).extracting(GrantPreview.MatchedSite::domain)
                .containsExactly("checkout.test.example.com");
    }

    @Test
    @DisplayName("a global grant reaches everything and says so")
    void globalReachesEverything() {
        GrantPreview preview = preview(ScopeType.GLOBAL, null, null);

        assertThat(preview.totalMatched()).isEqualTo(4);
        assertThat(preview.coversFutureDomains()).isTrue();
    }

    /**
     * The number that makes the preview worth reading: how much this actually adds. A grant that
     * duplicates access the subject already holds is very different from one that opens a
     * namespace to them for the first time.
     */
    @Test
    @DisplayName("sites the subject can already reach are marked, and excluded from the new count")
    void alreadyReachableSitesAreMarked() {
        grants.grant(new PermissionCommands.Grant(SubjectType.USER, "grace",
                ScopeType.DOMAIN_PATTERN, null, null, "app.example.com", PermissionLevel.MANAGE, null));

        GrantPreview preview = preview(ScopeType.DOMAIN_PATTERN, "*.example.com", null);

        assertThat(preview.matched())
                .filteredOn(GrantPreview.MatchedSite::alreadyReachable)
                .extracting(GrantPreview.MatchedSite::domain)
                .containsExactly("app.example.com");
        assertThat(preview.newlyVisibleToSubject())
                .describedAs("two of the three are genuinely new")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("previewing is refused for a scope the caller could not grant")
    void previewIsAuthorisedLikeTheGrant() {
        // An operator with no grants holds no authority over any namespace, so they may neither
        // create this grant nor inspect what it would reach.
        caller.actAs("grace", java.util.Set.of(),
                net.xiidea.enginx.domain.permission.GlobalRole.OPERATOR);

        assertThatThrownBy(() -> preview(ScopeType.DOMAIN_PATTERN, "*.example.com", null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
