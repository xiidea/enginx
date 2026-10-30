package net.xiidea.enginx.proxy;

import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.proxy.ProtectionInput;
import net.xiidea.enginx.application.proxy.ProxySiteChanges;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestAgent;
import net.xiidea.enginx.support.TestCertificateProvider;
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

/** A change deploys itself exactly when it changes what the host serves. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class})
class DeployOnSaveIntegrationTest extends AbstractIntegrationTest {

    private static final String DOMAIN = "app.example.com";

    @Autowired
    private ProxySiteChanges changes;
    @Autowired
    private CertificateService certificates;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestCertificateProvider authority;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxDispatcherJob dispatcher;
    @Autowired
    private ConfigBundleRepository bundles;
    @Autowired
    private ProxySiteService service;

    private UUID instanceId;
    private UUID certificateId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate certificates, acme_accounts, deployments, config_bundles, outbox_messages, "
                + "permission_grants, domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        authority.reset();
        caller.actAsSuperAdmin();
        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-deploy-on-save", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        certificateId = certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert", List.of(DOMAIN), true, 30)).id();
        ProxySiteChanges.Result created = changes.create(new ProxySiteCommands.Create(spec("Site", 8080),
                AdminState.ENABLED, basic("alice", "correct horse")));
        siteId = created.site().id();
        assertThat(created.deployment()).describedAs("a new site always deploys").isNotNull();
    }

    @Test
    @DisplayName("a change to what is rendered deploys, in the same transaction as the save")
    void renderedChangesDeploy() {
        ProxySiteChanges.Result result = update(spec("Site", 9090), null);

        assertThat(result.deployment()).isNotNull();
        assertThat(queuedFor(result.deployment().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("renaming the site changes nothing the host serves, so nothing is deployed")
    void aNameOnlyEditDoesNotDeploy() {
        assertThat(update(spec("A better name", 8080), null).deployment()).isNull();
    }

    @Test
    @DisplayName("a new password changes only the htpasswd file, and still deploys")
    void aPasswordChangeDeploys() {
        assertThat(update(spec("Site", 8080), basic("alice", "battery staple")).deployment()).isNotNull();
        assertThat(update(spec("Site", 8080), basic("alice", null)).deployment())
                .describedAs("keeping the password is no change").isNull();
    }

    @Test
    @DisplayName("disabling and enabling deploy, and enabling an enabled site does not")
    void stateChangesDeploy() {
        assertThat(changes.enable(siteId).deployment()).isNull();
        assertThat(changes.disable(siteId).deployment()).isNotNull();
        assertThat(changes.enable(siteId).deployment()).isNotNull();
    }

    @Test
    @DisplayName("signing everyone out of a login page deploys the new key")
    void signOutDeploys() {
        update(spec("Site", 8080), new ProtectionInput(ProtectionMode.BASIC,
                List.of(new ProtectionInput.BasicUser("alice", null)), null, null, new ProtectionInput.LoginPage(3600)));
        assertThat(changes.signOutEveryone(siteId).deployment()).isNotNull();
    }

    @Test
    @DisplayName("saves made while a deployment is still pending share it: it renders after they commit")
    void quickSavesShareOneDeployment() {
        UUID first = update(spec("Site", 9090), null).deployment().id();
        UUID second = update(spec("Site", 9091), null).deployment().id();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from deployments", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a save during a deployment that has already rendered is kept, not refused or rolled back")
    void aSaveDuringARenderedDeploymentIsKept() {
        // What a pull host's deployment looks like while it waits for the host: rendered, in progress.
        jdbc.update("update deployments set status = 'IN_PROGRESS'");

        ProxySiteChanges.Result result = update(spec("Site", 9090), null);

        assertThat(result.deployment()).isNull();
        assertThat(result.site().spec().upstreams().getFirst().port()).isEqualTo(9090);
        assertThat(jdbc.queryForObject("select count(*) from deployments", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting a site its host serves deploys, and the site leaves the host")
    void deletingAServedSiteDeploys() {
        dispatcher.drainOnce();
        assertThat(served()).isTrue();

        Deployment removal = changes.delete(siteId, null);
        assertThat(removal).isNotNull();
        dispatcher.drainOnce();

        assertThat(served()).isFalse();
    }

    @Test
    @DisplayName("deleting a site its host never served deploys nothing")
    void deletingAnUnservedSiteDoesNotDeploy() {
        // The site's first deployment is still pending: the host has nothing of it.
        assertThat(changes.delete(siteId, null)).isNull();
    }

    @Test
    @DisplayName("a site disabled without deploying is still served, so deleting it deploys")
    void deletingADisabledSiteStillServedDeploys() {
        dispatcher.drainOnce();
        service.disable(siteId);

        assertThat(changes.delete(siteId, null)).isNotNull();
    }

    private boolean served() {
        return bundles.findActiveForInstance(instanceId).orElseThrow().files().stream()
                .anyMatch(file -> file.path().equals("conf.d/" + DOMAIN + ".conf"));
    }

    private ProxySiteChanges.Result update(ProxySiteSpec spec, ProtectionInput protection) {
        return changes.update(new ProxySiteCommands.Update(siteId, spec, null, protection));
    }

    private Integer queuedFor(UUID deploymentId) {
        return jdbc.queryForObject("select count(*) from outbox_messages where aggregate_id = ?", Integer.class, deploymentId);
    }

    private static ProtectionInput basic(String user, String password) {
        return new ProtectionInput(ProtectionMode.BASIC, List.of(new ProtectionInput.BasicUser(user, password)));
    }

    private ProxySiteSpec spec(String name, int port) {
        return new ProxySiteSpec(name, DomainName.of(DOMAIN), instanceId, TimeWindow.unbounded(), true, true, false,
                false, certificateId, LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", port)), List.of(), List.of());
    }
}
