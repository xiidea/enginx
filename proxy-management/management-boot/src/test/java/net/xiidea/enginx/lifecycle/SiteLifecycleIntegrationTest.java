package net.xiidea.enginx.lifecycle;

import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.lifecycle.LifecycleSweepResult;
import net.xiidea.enginx.application.lifecycle.SiteLifecycleService;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.SiteStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expiration and activation lifecycle, end to end against PostgreSQL.
 *
 * <p>These cover the outcomes the brief names directly: an expired site is disabled automatically,
 * and its configuration is removed from the host. The sweep and the deployment it triggers are
 * the real code paths; only the agent is faked.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
class SiteLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private SiteLifecycleService lifecycle;
    @Autowired
    private OutboxDispatcherJob dispatcher;
    @Autowired
    private DeploymentService deployments;
    @Autowired
    private ConfigBundleRepository bundles;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private TestAgent agent;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate deployments, config_bundles, outbox_messages, permission_grants, "
                + "domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        agent.reset();
        caller.actAsSuperAdmin();

        NginxInstance instance = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-lifecycle",
                "nginx", "https://nginx:8443", "A".repeat(64), "TEST", Instant.now()));
        instanceId = instance.id();
    }

    @Test
    @DisplayName("an expired site is marked EXPIRED and its configuration leaves the host")
    void expirySweepRemovesTheSiteFromTheBundle() {
        ProxySite permanent = create("permanent.example.com", null);
        ProxySite temporary = create("temporary.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();

        assertThat(activeBundle().siteIds()).containsExactlyInAnyOrder(permanent.id(), temporary.id());

        expireInThePast(temporary.id());
        LifecycleSweepResult result = lifecycle.sweep();
        dispatcher.drainOnce();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(sites.get(temporary.id()).status()).isEqualTo(SiteStatus.EXPIRED);

        ConfigBundle active = activeBundle();
        assertThat(active.siteIds()).containsExactly(permanent.id());
        assertThat(active.files()).noneMatch(file -> file.path().contains("temporary.example.com"));
        // The site around it is untouched, which is the point of rendering the whole instance
        // from current state rather than patching one file.
        assertThat(active.files()).anyMatch(file -> file.path().contains("permanent.example.com"));
    }

    @Test
    @DisplayName("a pending site becomes ACTIVE and its configuration reaches the host")
    void activationSweepAddsTheSiteToTheBundle() {
        ProxySite scheduled = createPending("later.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(scheduled.status()).isEqualTo(SiteStatus.PENDING);

        deployAndDrain();
        assertThat(activeBundle().siteIds()).isEmpty();

        jdbc.update("update proxy_sites set active_from = now() - interval '1 minute' where id = ?",
                scheduled.id());
        LifecycleSweepResult result = lifecycle.sweep();
        dispatcher.drainOnce();

        assertThat(result.activated()).isEqualTo(1);
        assertThat(sites.get(scheduled.id()).status()).isEqualTo(SiteStatus.ACTIVE);
        assertThat(activeBundle().siteIds()).containsExactly(scheduled.id());
    }

    @Test
    @DisplayName("the sweep asks what is due now, so an expiry missed while the app was down still lands")
    void anExpiryMissedWhileDownIsStillApplied() {
        ProxySite site = create("gone.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();

        // Simulate the process having been stopped across the expiry: nothing ran at the moment
        // it passed, and there is no alarm waiting to be replayed.
        jdbc.update("update proxy_sites set expires_at = now() - interval '3 days' where id = ?", site.id());

        lifecycle.sweep();
        dispatcher.drainOnce();

        assertThat(sites.get(site.id()).status()).isEqualTo(SiteStatus.EXPIRED);
        assertThat(activeBundle().siteIds()).isEmpty();
    }

    @Test
    @DisplayName("a second sweep finds nothing to do, so the transition happens exactly once")
    void sweepingTwiceIsIdempotent() {
        ProxySite site = create("once.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();
        expireInThePast(site.id());

        assertThat(lifecycle.sweep().expired()).isEqualTo(1);
        assertThat(lifecycle.sweep().expired()).isZero();

        Integer expiryAudits = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'PROXY_SITE_EXPIRED' and resource_id = ?",
                Integer.class, site.id());
        assertThat(expiryAudits).isEqualTo(1);
    }

    @Test
    @DisplayName("expiring a site republishes the instance exactly once, however many sites expired")
    void oneDeploymentPerInstanceRegardlessOfHowManySitesExpired() {
        ProxySite first = create("a.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        ProxySite second = create("b.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();
        int deploymentsBefore = countDeployments();

        expireInThePast(first.id());
        expireInThePast(second.id());
        LifecycleSweepResult result = lifecycle.sweep();

        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.instancesQueued()).isEqualTo(1);
        assertThat(countDeployments()).isEqualTo(deploymentsBefore + 1);
    }

    @Test
    @DisplayName("the status change and the redeployment commit together, so neither can happen alone")
    void statusChangeAndDeploymentAreAtomic() {
        ProxySite site = create("atomic.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();
        expireInThePast(site.id());

        lifecycle.sweep();

        // The status is committed and the work to republish is queued in the same transaction.
        // A site marked expired with no deployment behind it would still be serving traffic.
        assertThat(sites.get(site.id()).status()).isEqualTo(SiteStatus.EXPIRED);
        Integer queued = jdbc.queryForObject(
                "select count(*) from outbox_messages where status = 'NEW'", Integer.class);
        assertThat(queued).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed deployment does not stop the expiry from taking effect in the database")
    void expiryStillAppliesWhenTheAgentIsUnreachable() {
        ProxySite site = create("unreachable.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();
        expireInThePast(site.id());

        agent.behave(TestAgent.Behaviour.UNREACHABLE);
        lifecycle.sweep();
        dispatcher.drainOnce();

        // The record of intent is correct even though the host has not caught up yet, and the
        // outbox keeps retrying rather than dropping the change.
        assertThat(sites.get(site.id()).status()).isEqualTo(SiteStatus.EXPIRED);
        String outboxStatus = jdbc.queryForObject(
                "select status from outbox_messages order by created_at desc limit 1", String.class);
        assertThat(outboxStatus).isEqualTo("NEW");

        agent.behave(TestAgent.Behaviour.SUCCEED);
        jdbc.update("update outbox_messages set next_attempt_at = now() - interval '1 minute'");
        dispatcher.drainOnce();

        assertThat(activeBundle().siteIds()).isEmpty();
    }

    @Test
    @DisplayName("renewing an expired site brings it back on the next deployment")
    void renewingRestoresTheSite() {
        ProxySite site = create("renewed.example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        deployAndDrain();
        expireInThePast(site.id());
        lifecycle.sweep();
        dispatcher.drainOnce();
        assertThat(activeBundle().siteIds()).isEmpty();

        sites.renew(new ProxySiteCommands.Renew(site.id(), Instant.now().plus(30, ChronoUnit.DAYS), null));
        assertThat(sites.get(site.id()).status()).isEqualTo(SiteStatus.ACTIVE);

        deployAndDrain();
        assertThat(activeBundle().siteIds()).containsExactly(site.id());
    }

    // ---- helpers -----------------------------------------------------------

    private void expireInThePast(UUID siteId) {
        jdbc.update("update proxy_sites set expires_at = now() - interval '1 minute' where id = ?", siteId);
    }

    private ConfigBundle activeBundle() {
        return bundles.findActiveForInstance(instanceId).orElseThrow();
    }

    private int countDeployments() {
        Integer count = jdbc.queryForObject("select count(*) from deployments", Integer.class);
        return count == null ? 0 : count;
    }

    /** Publishes current state to the instance, the way a lifecycle transition would. */
    private void deployAndDrain() {
        deployments.queueSystemDeployment(instanceId, DeploymentTrigger.MANUAL, Map.of("reason", "test"));
        dispatcher.drainOnce();
    }

    private ProxySite create(String domain, Instant expiresAt) {
        return sites.create(new ProxySiteCommands.Create(
                spec(domain, new TimeWindow(null, expiresAt)), AdminState.ENABLED));
    }

    private ProxySite createPending(String domain, Instant activeFrom) {
        return sites.create(new ProxySiteCommands.Create(
                spec(domain, new TimeWindow(activeFrom, null)), AdminState.ENABLED));
    }

    private ProxySiteSpec spec(String domain, TimeWindow window) {
        return new ProxySiteSpec("Site " + domain, DomainName.of(domain), instanceId, window,
                false, false, false, false, null, LoadBalancingMethod.ROUND_ROBIN,
                ProxyTimeouts.defaults(), List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)),
                List.of(), List.of());
    }
}
