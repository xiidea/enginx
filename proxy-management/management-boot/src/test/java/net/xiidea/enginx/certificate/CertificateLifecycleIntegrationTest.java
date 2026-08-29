package net.xiidea.enginx.certificate;

import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateMonitorService;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.CertificateStatus;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.deployment.CertificateMaterialProvider;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.ConflictException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The certificate lifecycle against real PostgreSQL and real X.509 material.
 *
 * <p>The authority is substituted, but the certificates it returns are genuine self-signed ones,
 * so metadata parsing, SAN-based domain recording, encryption at rest, expiry monitoring and
 * bundle rendering all run against the same shapes they would in production.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class})
class CertificateLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CertificateService certificates;
    @Autowired
    private CertificateMonitorService monitor;
    @Autowired
    private CertificateRepository repository;
    @Autowired
    private CertificateMaterialProvider material;
    @Autowired
    private SecretEncryption encryption;
    @Autowired
    private ProxySiteService sites;
    @Autowired
    private ConfigBundleRepository bundles;
    @Autowired
    private OutboxDispatcherJob dispatcher;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestCertificateProvider authority;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate certificates, acme_accounts, deployments, config_bundles, outbox_messages, "
                + "permission_grants, domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        authority.reset();
        caller.actAsSuperAdmin();

        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-certificates", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
    }

    @Test
    @DisplayName("an issued certificate records what it actually covers, read from the certificate itself")
    void issuanceRecordsMetadataFromTheCertificate() {
        Certificate certificate = request("app.example.com", "api.example.com");

        assertThat(certificate.status()).isEqualTo(CertificateStatus.VALID);
        // Taken from the SAN, not from the request: if an authority issued something narrower,
        // the platform must know what it actually holds.
        assertThat(certificate.domains()).containsExactlyInAnyOrder("app.example.com", "api.example.com");
        assertThat(certificate.metadata().notAfter()).isAfter(Instant.now());
        assertThat(certificate.covers("app.example.com")).isTrue();
        assertThat(certificate.covers("other.example.com")).isFalse();
    }

    @Test
    @DisplayName("the private key is stored encrypted and never in the clear")
    void privateKeysAreEncryptedAtRest() {
        Certificate certificate = request("app.example.com");

        String stored = jdbc.queryForObject(
                "select encode(ciphertext, 'escape') from certificate_secrets where certificate_id = ?",
                String.class, certificate.id());

        assertThat(stored).doesNotContain("PRIVATE KEY");
        assertThat(jdbc.queryForObject("select kek_id from certificate_secrets where certificate_id = ?",
                String.class, certificate.id())).isEqualTo("test");

        // It round-trips through the one path that is allowed to decrypt.
        assertThat(encryption.decrypt(repository.findPrivateKey(certificate.id()).orElseThrow()))
                .contains("PRIVATE KEY");
    }

    @Test
    @DisplayName("a failed issuance leaves an auditable record rather than nothing")
    void failedIssuanceIsRecorded() {
        authority.fail(true);

        Certificate certificate = request("broken.example.com");

        assertThat(certificate.status()).isEqualTo(CertificateStatus.ERROR);
        assertThat(certificate.lastError()).contains("refused the request");
        assertThat(repository.findPrivateKey(certificate.id())).isEmpty();
    }

    @Test
    @DisplayName("a failed renewal keeps the material that is still serving traffic")
    void failedRenewalKeepsTheWorkingCertificate() {
        Certificate certificate = request("app.example.com");
        String serialBefore = certificate.metadata().serialNumber();

        authority.fail(true);
        Certificate afterFailure = certificates.renew(certificate.id());

        assertThat(afterFailure.lastError()).isNotNull();
        // Still usable: taking a working certificate away because a renewal failed would break
        // the very sites it is protecting.
        assertThat(afterFailure.metadata().serialNumber()).isEqualTo(serialBefore);
        assertThat(afterFailure.status().isUsable()).isTrue();
    }

    @Test
    @DisplayName("expiry monitoring reclassifies certificates from their own dates")
    void monitoringTracksExpiry() {
        Certificate certificate = request("app.example.com");
        assertThat(certificate.status()).isEqualTo(CertificateStatus.VALID);

        jdbc.update("update certificates set expires_at = now() - interval '1 day' where id = ?", certificate.id());
        assertThat(monitor.refreshStatuses()).isEqualTo(1);

        assertThat(repository.findById(certificate.id()).orElseThrow().status())
                .isEqualTo(CertificateStatus.EXPIRED);

        Integer alerts = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'CERTIFICATE_EXPIRING' and resource_id = ?",
                Integer.class, certificate.id());
        assertThat(alerts).isEqualTo(1);
    }

    @Test
    @DisplayName("a certificate inside its renewal window is renewed automatically")
    void automaticRenewal() {
        authority.issuesCertificatesValidFor(Duration.ofDays(10));
        Certificate certificate = request("app.example.com");
        String serialBefore = certificate.metadata().serialNumber();

        // Ten days left against a thirty-day window, so it is due.
        authority.issuesCertificatesValidFor(Duration.ofDays(90));
        assertThat(monitor.renewDue()).isEqualTo(1);

        Certificate renewed = repository.findById(certificate.id()).orElseThrow();
        assertThat(renewed.metadata().serialNumber()).isNotEqualTo(serialBefore);
        assertThat(renewed.status()).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    @DisplayName("a certificate that has never been issued is not swept up by automatic renewal")
    void neverIssuedCertificatesAreNotAutoRenewed() {
        authority.fail(true);
        request("never.example.com");
        int issuedBefore = authority.issuedCount();

        // Otherwise the sweep races an issuance still in flight, and retries on a timer a request
        // that cannot succeed until someone points the domain here — spending rate limit each time.
        assertThat(monitor.renewDue()).isZero();
        assertThat(authority.issuedCount()).isEqualTo(issuedBefore);
    }

    @Test
    @DisplayName("an uploaded certificate is stored and deployed, but never renewed automatically")
    void manualCertificatesAreNotAutoRenewed() {
        Certificate acme = request("app.example.com");
        String chain = acme.fullChainPem();
        String key = encryption.decrypt(repository.findPrivateKey(acme.id()).orElseThrow());

        Certificate uploaded = certificates.upload(new CertificateCommands.Upload("uploaded", chain, key));

        assertThat(uploaded.provider().name()).isEqualTo("MANUAL");
        assertThat(uploaded.autoRenew()).isFalse();
        assertThat(uploaded.status()).isEqualTo(CertificateStatus.VALID);
        assertThat(uploaded.domains()).containsExactly("app.example.com");
    }

    @Test
    @DisplayName("an SSL site renders with its certificate, and the private key is marked sensitive")
    void certificateMaterialReachesTheBundle() {
        Certificate certificate = request("secure.example.com");
        sites.create(new ProxySiteCommands.Create(sslSpec("secure.example.com", certificate.id()),
                AdminState.ENABLED));

        deployAndDrain();
        ConfigBundle bundle = bundles.findActiveForInstance(instanceId).orElseThrow();

        assertThat(bundle.files()).extracting(file -> file.path())
                .contains("certs/secure.example.com/fullchain.pem", "certs/secure.example.com/privkey.pem");
        assertThat(bundle.files()).filteredOn(file -> file.path().endsWith("privkey.pem"))
                .allMatch(file -> file.sensitive());
        // The readable view never carries key material, at any permission level.
        assertThat(bundle.publicFiles()).noneMatch(file -> file.path().endsWith("privkey.pem"));
    }

    @Test
    @DisplayName("expired or revoked material is withheld from deployment rather than shipped")
    void unusableMaterialIsNotDeployed() {
        Certificate certificate = request("secure.example.com");
        assertThat(material.materialFor(certificate.id())).isPresent();

        certificates.revoke(certificate.id());

        // Deploying a revoked certificate would replace a working one with something every client
        // rejects, which is worse than the deployment failing.
        assertThat(material.materialFor(certificate.id())).isEmpty();
        assertThat(authority.revokeCalled()).isTrue();
    }

    @Test
    @DisplayName("a certificate still attached to a site cannot be deleted")
    void inUseCertificatesAreProtected() {
        Certificate certificate = request("secure.example.com");
        sites.create(new ProxySiteCommands.Create(sslSpec("secure.example.com", certificate.id()),
                AdminState.ENABLED));

        assertThatThrownBy(() -> certificates.delete(certificate.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("attached to one or more proxy sites");
    }

    // ---- helpers -----------------------------------------------------------

    private Certificate request(String... domains) {
        return certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert for " + domains[0], List.of(domains), true, 30));
    }

    private ProxySiteSpec sslSpec(String domain, UUID certificateId) {
        return new ProxySiteSpec("Site " + domain, DomainName.of(domain), instanceId,
                TimeWindow.unbounded(), true, true, false, false, certificateId,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of());
    }

    private void deployAndDrain() {
        jdbc.update("insert into outbox_messages (id, aggregate_type, aggregate_id, message_type, status, "
                + "attempts, next_attempt_at, created_at) select ?, 'DEPLOYMENT', ?, 'DISPATCH_DEPLOYMENT', "
                + "'NEW', 0, now(), now()", UUID.randomUUID(), queueDeployment());
        dispatcher.drainOnce();
    }

    private UUID queueDeployment() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into deployments (id, nginx_instance_id, trigger_type, status, attempt, "
                + "idempotency_key, created_by, created_at) values (?, ?, 'MANUAL', 'PENDING', 0, ?, 'test', now())",
                id, instanceId, id.toString());
        return id;
    }
}
