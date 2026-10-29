package net.xiidea.enginx.proxy;

import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
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

    private ProxySiteSpec spec(List<LocationRule> locations) {
        return new ProxySiteSpec("Site", DomainName.of("app.example.com"), instanceId, TimeWindow.unbounded(),
                false, false, false, false, null, LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), locations);
    }
}
