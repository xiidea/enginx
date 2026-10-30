package net.xiidea.enginx.proxy;

import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import net.xiidea.enginx.domain.proxy.LocationProxySettings;
import net.xiidea.enginx.domain.proxy.HeaderDirection;
import net.xiidea.enginx.domain.proxy.ProxySiteHeader;
import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestAgent;
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

/** Locations survive an update that does not mention them: the console's old form never did. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
class SiteLocationsIntegrationTest extends AbstractIntegrationTest {

    private static final List<LocationRule> CUSTOM = List.of(
            new LocationRule("/", LocationMatchType.PREFIX, 0),
            new LocationRule("/health", LocationMatchType.EXACT, 1));

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private ProxySite site;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate deployments, config_bundles, outbox_messages, permission_grants, "
                + "domain_group_members, domain_groups, proxy_sites, nginx_instances restart identity cascade");
        caller.actAsSuperAdmin();
        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-locations", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        site = sites.create(new ProxySiteCommands.Create(spec(CUSTOM), AdminState.ENABLED));
    }

    @Test
    @DisplayName("an update that leaves locations out keeps them")
    void omittedLocationsAreKept() {
        ProxySite updated = sites.update(new ProxySiteCommands.Update(site.id(), spec(List.of()), null, null, true));

        assertThat(updated.spec().locations()).extracting(LocationRule::pathPattern).containsExactly("/", "/health");
    }

    @Test
    @DisplayName("an update that sends locations replaces them, and an empty list means the root alone")
    void sentLocationsReplace() {
        ProxySite updated = sites.update(new ProxySiteCommands.Update(site.id(), spec(List.of()), null, null, false));

        assertThat(updated.spec().locations()).containsExactly(LocationRule.root());
    }

    @Test
    @DisplayName("the audit trail records locations, so a change to them can be seen afterwards")
    void auditRecordsLocations() {
        String after = jdbc.queryForObject(
                "select after_state::text from audit_logs where resource_id = ? order by occurred_at desc limit 1",
                String.class, site.id());
        assertThat(after).contains("= /health");
    }

    @Test
    @DisplayName("a location's own pool, settings and public access are stored, and an edit changes only what changed")
    void locationSettingsRoundTrip() {
        LocationRule api = new LocationRule("/api/", LocationMatchType.PREFIX, 1, false,
                List.of(UpstreamTarget.of("http", "10.0.0.30", 9000), UpstreamTarget.of("http", "10.0.0.31", 9000)),
                LoadBalancingMethod.LEAST_CONN,
                new LocationProxySettings(5, 300, null, 52_428_800L, true,
                        List.of(new ProxySiteHeader(HeaderDirection.REQUEST, "X-Service", "api"))));
        LocationRule health = new LocationRule("/health", LocationMatchType.EXACT, 2, true, List.of(), null, null);
        List<LocationRule> routed = List.of(LocationRule.root(), api, health);

        sites.update(new ProxySiteCommands.Update(site.id(), spec(routed), null, null, false));
        assertThat(sites.get(site.id()).spec().locations()).isEqualTo(routed);

        // One server dropped, one header changed: the rest keeps its rows.
        String keptRow = jdbc.queryForObject("select id::text from proxy_site_location_upstreams where host = '10.0.0.30'",
                String.class);
        LocationRule edited = new LocationRule("/api/", LocationMatchType.PREFIX, 1, false,
                List.of(UpstreamTarget.of("http", "10.0.0.30", 9000)), null,
                new LocationProxySettings(null, 300, null, null, null,
                        List.of(new ProxySiteHeader(HeaderDirection.REQUEST, "X-Service", "api-v2"))));
        List<LocationRule> after = List.of(LocationRule.root(), edited, health);
        sites.update(new ProxySiteCommands.Update(site.id(), spec(after), null, null, false));

        assertThat(sites.get(site.id()).spec().locations()).isEqualTo(after);
        assertThat(jdbc.queryForObject("select id::text from proxy_site_location_upstreams where host = '10.0.0.30'",
                String.class)).isEqualTo(keptRow);
        assertThat(jdbc.queryForObject("select count(*) from proxy_site_location_upstreams", Integer.class)).isEqualTo(1);

        // Removing the location removes what was its own.
        sites.update(new ProxySiteCommands.Update(site.id(), spec(List.of(LocationRule.root())), null, null, false));
        assertThat(jdbc.queryForObject("select count(*) from proxy_site_location_upstreams", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from proxy_site_location_headers", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the database refuses a public root and a header value that could escape its quotes")
    void theDatabaseRepeatsTheRules() {
        UUID root = jdbc.queryForObject("select id from proxy_site_locations where path_pattern = '/'", UUID.class);
        assertThatThrownBy(() -> jdbc.update("update proxy_site_locations set public_access = true where id = ?", root))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into proxy_site_location_headers (id, location_id, header_name, "
                + "header_value) values (gen_random_uuid(), ?, 'X-Evil', 'a\"; return 200')", root))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the audit trail says which locations are public and where their own pools are")
    void auditDescribesLocationSettings() {
        LocationRule health = new LocationRule("/health", LocationMatchType.EXACT, 1, true, List.of(), null, null);
        sites.update(new ProxySiteCommands.Update(site.id(), spec(List.of(LocationRule.root(), health)), null, null, false));
        String after = jdbc.queryForObject(
                "select after_state::text from audit_logs where resource_id = ? order by occurred_at desc limit 1",
                String.class, site.id());
        assertThat(after).contains("= /health [public]");
    }

    private ProxySiteSpec spec(List<LocationRule> locations) {
        return new ProxySiteSpec("Site", DomainName.of("app.example.com"), instanceId, TimeWindow.unbounded(),
                false, false, false, false, null, LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), locations);
    }
}
