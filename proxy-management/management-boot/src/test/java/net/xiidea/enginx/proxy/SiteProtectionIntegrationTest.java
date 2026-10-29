package net.xiidea.enginx.proxy;

import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.proxy.ProtectionInput;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestAgent;
import net.xiidea.enginx.support.TestCertificateProvider;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.apache.commons.codec.digest.Sha2Crypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Basic authentication from request to bundle: hashing, write-only passwords, and the file the host gets. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class})
class SiteProtectionIntegrationTest extends AbstractIntegrationTest {

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
    private TestCertificateProvider authority;
    @Autowired
    private TestAgent agent;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private UUID certificateId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate certificates, acme_accounts, deployments, config_bundles, outbox_messages, "
                + "permission_grants, domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        authority.reset();
        agent.reset();
        caller.actAsSuperAdmin();

        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-protection", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        certificateId = certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert for " + DOMAIN, List.of(DOMAIN), true, 30)).id();
    }

    @Test
    @DisplayName("a password is stored only as a SHA-512-crypt hash that verifies against it")
    void passwordsAreHashed() {
        ProxySite site = create(user("alice", "correct horse"));

        String hash = site.spec().protection().basicUsers().getFirst().passwordHash();
        assertThat(hash).startsWith("$6$").doesNotContain("correct horse");
        assertThat(Sha2Crypt.sha512Crypt("correct horse".getBytes(StandardCharsets.UTF_8), hash)).isEqualTo(hash);
    }

    @Test
    @DisplayName("a user sent without a password keeps the one they have, and a new user without one is refused")
    void passwordsAreWriteOnly() {
        ProxySite site = create(user("alice", "correct horse"));
        String aliceHash = site.spec().protection().basicUsers().getFirst().passwordHash();

        ProxySite updated = sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                basic(user("alice", null), user("bob", "battery staple"))));

        assertThat(updated.spec().protection().basicUsers())
                .extracting(u -> u.username())
                .containsExactly("alice", "bob");
        assertThat(updated.spec().protection().basicUsers().getFirst().passwordHash()).isEqualTo(aliceHash);

        assertThatThrownBy(() -> sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                basic(user("alice", null), user("carol", null)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("carol");
    }

    @Test
    @DisplayName("an update that leaves protection out keeps it, so an older client cannot remove it by omission")
    void omittedProtectionIsKept() {
        ProxySite site = create(user("alice", "correct horse"));

        ProxySite updated = sites.update(new ProxySiteCommands.Update(site.id(), spec(), null));

        assertThat(updated.spec().protection().mode()).isEqualTo(ProtectionMode.BASIC);
        assertThat(sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                new ProtectionInput(ProtectionMode.NONE, List.of()))).spec().protection().isProtected())
                .isFalse();
    }

    @Test
    @DisplayName("a login page reads back as saved, gets a key, and makes sign-out-everyone possible")
    void loginPageRoundTrips() {
        ProxySite site = sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED,
                new ProtectionInput(ProtectionMode.BASIC, List.of(user("alice", "correct horse")), null, null,
                        new ProtectionInput.LoginPage(3600))));

        ProxySite reloaded = sites.get(site.id());
        assertThat(reloaded.spec().protection().basicLoginPage().sessionTtl().toSeconds()).isEqualTo(3600);
        assertThat(reloaded.spec().protection().usesGate()).isTrue();
        sites.signOutEveryone(site.id());

        ProxySite prompt = sites.update(new ProxySiteCommands.Update(site.id(), spec(), null,
                new ProtectionInput(ProtectionMode.BASIC, List.of(user("alice", null)))));
        assertThat(prompt.spec().protection().basicLoginPage()).isNull();
        assertThatThrownBy(() -> sites.signOutEveryone(site.id())).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("short passwords are refused")
    void shortPasswordsAreRefused() {
        assertThatThrownBy(() -> create(user("alice", "short")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("8 to 128");
    }

    @Test
    @DisplayName("the htpasswd file reaches the host worker-readable, and stays so when read back")
    void htpasswdIsDeployedWorkerReadable() {
        create(user("alice", "correct horse"));

        assertThat(deploy().status()).isEqualTo(DeploymentStatus.SUCCESS);
        // Learned before rendering, and kept after the activation rather than cleared by it.
        assertThat(instances.findById(instanceId).orElseThrow().agentVersion()).isEqualTo("dev");

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        BundleFile htpasswd = active.files().stream()
                .filter(file -> file.path().equals("auth/" + DOMAIN + "/htpasswd"))
                .findFirst().orElseThrow();
        assertThat(htpasswd.access()).isEqualTo(BundleFile.Access.WORKER_READABLE);
        assertThat(htpasswd.content()).startsWith("alice:$6$");
        assertThat(active.publicFiles()).noneMatch(file -> file.path().startsWith("auth/"));
        assertThat(active.files()).filteredOn(file -> file.path().endsWith("privkey.pem"))
                .allMatch(file -> file.access() == BundleFile.Access.PRIVATE);
    }

    @Test
    @DisplayName("a host whose agent predates worker-readable files is never sent a protected site")
    void olderAgentsAreRefused() {
        NginxInstance instance = instances.findById(instanceId).orElseThrow();
        instance.observed(InstanceStatus.ONLINE, "1.27.5", "0.1.2", Instant.now());
        instances.save(instance);
        create(user("alice", "correct horse"));

        Deployment failed = deploy();

        assertThat(failed.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(agent.stageCalls()).isZero();
    }

    @Test
    @DisplayName("VERIFY asks the host whether the site refuses a visitor who has not signed in")
    void verifyChecksProtection() {
        create(user("alice", "correct horse"));

        Deployment done = deploy();

        assertThat(done.verificationFailed()).isFalse();
        assertThat(done.events()).filteredOn(event -> event.phase().name().equals("VERIFY"))
                .singleElement().satisfies(event ->
                        assertThat(event.detail()).contains("refuses visitors who have not signed in"));
    }

    @Test
    @DisplayName("a protected site that lets anyone through fails VERIFY, whatever let them through")
    void anUnenforcedProtectionFailsVerify() {
        create(user("alice", "correct horse"));
        agent.bypassProtection(true);

        Deployment done = deploy();

        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(done.verificationFailed()).isTrue();
        assertThat(done.events()).filteredOn(event -> event.phase().name().equals("VERIFY"))
                .singleElement().satisfies(event ->
                        assertThat(event.detail()).contains("protection is not enforced"));
    }

    @Test
    @DisplayName("the audit trail records who may sign in, never a hash")
    void auditCarriesNoHashes() {
        ProxySite site = create(user("alice", "correct horse"));

        String after = jdbc.queryForObject(
                "select after_state::text from audit_logs where resource_id = ? order by occurred_at desc limit 1",
                String.class, site.id());
        assertThat(after).contains("alice").contains("BASIC").doesNotContain("$6$");
    }

    @Test
    @DisplayName("a gate mode the platform is not set up for is refused when saved, saying what is missing")
    void unconfiguredGateModesAreRefusedOnSave() {
        assertThatThrownBy(() -> sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED,
                new ProtectionInput(ProtectionMode.EMAIL_OTP, List.of(),
                        new ProtectionInput.EmailOtp(List.of("*@example.com"), null)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("GATE_RELAY_URL");
        assertThatThrownBy(() -> sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED,
                new ProtectionInput(ProtectionMode.OIDC, List.of(), null,
                        new ProtectionInput.Oidc(true, List.of(), List.of(), null)))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("GATE_OIDC_ISSUER_URI");
    }

    @Test
    @DisplayName("the platform says which modes it can serve, and why not the others")
    void protectionModesAreReported() {
        assertThat(sites.protectionModes())
                .extracting(m -> m.mode() + "=" + m.available())
                .containsExactly("NONE=true", "BASIC=true", "EMAIL_OTP=false", "OIDC=false");
        assertThat(sites.protectionModes()).filteredOn(m -> !m.available()).allMatch(m -> m.reason() != null);
    }

    // ---- helpers -----------------------------------------------------------

    private ProxySite create(ProtectionInput.BasicUser... users) {
        ProxySite site = sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED, basic(users)));
        siteId = site.id();
        return site;
    }

    private static ProtectionInput basic(ProtectionInput.BasicUser... users) {
        return new ProtectionInput(ProtectionMode.BASIC, List.of(users));
    }

    private static ProtectionInput.BasicUser user(String username, String password) {
        return new ProtectionInput.BasicUser(username, password);
    }

    private ProxySiteSpec spec() {
        return new ProxySiteSpec("Site " + DOMAIN, DomainName.of(DOMAIN), instanceId,
                TimeWindow.unbounded(), true, true, false, false, certificateId,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of());
    }

    private Deployment deploy() {
        Deployment queued = deployments.deploySite(siteId);
        dispatcher.drainOnce();
        return deployments.get(queued.id());
    }
}
