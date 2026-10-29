package net.xiidea.enginx.proxy;

import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.gate.GateKeys;
import net.xiidea.enginx.application.proxy.ProtectionInput;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.OidcRole;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
import net.xiidea.enginx.domain.proxy.ProxySite;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Identity provider sign-in from the platform's side: the policy, and what the gate is sent. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class})
@TestPropertySource(properties = {
        "enginx.gate.oidc.issuer-uri=https://id.example.com/realms/staff",
        "enginx.gate.oidc.backchannel-uri=http://keycloak:8080/realms/staff",
        "enginx.gate.oidc.client-id=enginx-sites"})
class OidcGateIntegrationTest extends AbstractIntegrationTest {

    private static final String DOMAIN = "private.example.com";

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private CertificateService certificates;
    @Autowired
    private DeploymentService deployments;
    @Autowired
    private OutboxDispatcherJob dispatcher;
    @Autowired
    private ConfigBundleRepository bundles;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private GateKeys keys;
    @Autowired
    private TestCertificateProvider authority;
    @Autowired
    private TestAgent agent;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private UUID certificateId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate certificates, acme_accounts, deployments, config_bundles, outbox_messages, "
                + "permission_grants, domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        authority.reset();
        agent.reset();
        caller.actAsSuperAdmin();
        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-oidc", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        certificateId = certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert for " + DOMAIN, List.of(DOMAIN), true, 30)).id();
    }

    @Test
    @DisplayName("users, realm roles and client roles read back as saved, and the site gets a key")
    void policyRoundTrips() {
        ProxySite site = create(new ProtectionInput.Oidc(false, List.of("Alice@Example.com", "bob"),
                List.of(new ProtectionInput.Oidc.Role(null, "auditor"),
                        new ProtectionInput.Oidc.Role("reports", "viewer")), 7200));

        ProxySite reloaded = sites.get(site.id());
        assertThat(reloaded.spec().protection().mode()).isEqualTo(ProtectionMode.OIDC);
        assertThat(reloaded.spec().protection().oidc().anyUser()).isFalse();
        assertThat(reloaded.spec().protection().oidc().users()).containsExactly("alice@example.com", "bob");
        assertThat(reloaded.spec().protection().oidc().roles())
                .containsExactly(OidcRole.realm("auditor"), new OidcRole("reports", "viewer"));
        assertThat(reloaded.spec().protection().sessionTtl().toSeconds()).isEqualTo(7200);
        assertThat(keys.reveal(site.id())).isPresent();
    }

    @Test
    @DisplayName("switching from a list to any user, and back to public, leaves no stale entries")
    void policyChangesReplaceEntries() {
        ProxySite site = create(new ProtectionInput.Oidc(false, List.of("bob"),
                List.of(new ProtectionInput.Oidc.Role("reports", "viewer")), null));

        ProxySite anyone = sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                new ProtectionInput(ProtectionMode.OIDC, List.of(), null,
                        new ProtectionInput.Oidc(true, List.of(), List.of(), null))));
        assertThat(anyone.spec().protection().oidc().anyUser()).isTrue();

        sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                new ProtectionInput(ProtectionMode.NONE, List.of())));
        assertThat(jdbc.queryForObject("select count(*) from proxy_site_oidc_users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from proxy_site_oidc_roles", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the gate is sent the platform's provider and the site's policy")
    void theGateFileIsDeployed() {
        ProxySite site = create(new ProtectionInput.Oidc(false, List.of(),
                List.of(new ProtectionInput.Oidc.Role("reports", "viewer")), null));

        Deployment deployment = deployments.deploySite(site.id());
        dispatcher.drainOnce();
        assertThat(deployments.get(deployment.id()).status()).isEqualTo(DeploymentStatus.SUCCESS);

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        BundleFile gate = active.files().stream()
                .filter(file -> file.path().equals("gate/sites/" + DOMAIN + ".json"))
                .findFirst().orElseThrow();
        assertThat(gate.content())
                .contains("\"issuer\": \"https://id.example.com/realms/staff\"")
                .contains("\"backchannel\": \"http://keycloak:8080/realms/staff\"")
                .contains("{\"client\": \"reports\", \"role\": \"viewer\"}");
        assertThat(gate.access()).isEqualTo(BundleFile.Access.WORKER_READABLE);
    }

    private ProxySite create(ProtectionInput.Oidc oidc) {
        return sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED,
                new ProtectionInput(ProtectionMode.OIDC, List.of(), null, oidc)));
    }

    private ProxySiteSpec spec() {
        return new ProxySiteSpec("Site " + DOMAIN, DomainName.of(DOMAIN), instanceId,
                TimeWindow.unbounded(), true, true, false, false, certificateId,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of());
    }
}
