package net.xiidea.enginx.deployment;

import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.infrastructure.persistence.adapter.BundleFileSecrets;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stored bundles keep their secrets sealed, and still deploy exactly what was rendered. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class})
class BundleSealingIntegrationTest extends AbstractIntegrationTest {

    private static final String DOMAIN = "secure.example.com";
    private static final String PRIVKEY = "certs/" + DOMAIN + "/privkey.pem";

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
    private BundleFileSecrets secrets;
    @Autowired
    private TestCertificateProvider authority;
    @Autowired
    private TestAgent agent;
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
        agent.reset();
        caller.actAsSuperAdmin();
        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-sealing", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        UUID certificateId = certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert for " + DOMAIN, List.of(DOMAIN), true, 30)).id();
        ProxySite site = sites.create(new ProxySiteCommands.Create(new ProxySiteSpec("Site", DomainName.of(DOMAIN),
                instanceId, TimeWindow.unbounded(), true, true, false, false, certificateId,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of()), AdminState.ENABLED));
        deployments.deploySite(site.id());
        dispatcher.drainOnce();
    }

    @Test
    @DisplayName("a private key is stored sealed, and read back as rendered")
    void sensitiveFilesAreSealed() {
        Map<String, Object> row = jdbc.queryForMap(
                "select content, content_ciphertext from config_bundle_files where relative_path = ?", PRIVKEY);
        assertThat(row.get("content")).isNull();
        assertThat(new String((byte[]) row.get("content_ciphertext"), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("PRIVATE KEY");

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        BundleFile key = active.files().stream().filter(f -> f.path().equals(PRIVKEY)).findFirst().orElseThrow();
        assertThat(key.content()).contains("PRIVATE KEY");
        assertThat(key.sha256()).isEqualTo(BundleFile.digest(key.content()));
    }

    @Test
    @DisplayName("public files stay in the clear: the bundle view and drift checks read them")
    void publicFilesAreNotSealed() {
        Integer clear = jdbc.queryForObject(
                "select count(*) from config_bundle_files where not sensitive and content is not null", Integer.class);
        Integer sealed = jdbc.queryForObject(
                "select count(*) from config_bundle_files where not sensitive and content_ciphertext is not null",
                Integer.class);
        assertThat(clear).isPositive();
        assertThat(sealed).isZero();
    }

    @Test
    @DisplayName("the database refuses a sensitive file in the clear")
    void plaintextSensitiveRowsAreRefused() {
        assertThatThrownBy(() -> jdbc.update(
                "update config_bundle_files set content = 'key', content_ciphertext = null, content_wrapped_dek = null, "
                        + "content_kek_id = null, content_cipher = null, content_iv = null where relative_path = ?",
                PRIVKEY))
                .hasMessageContaining("ck_bundle_file_sensitive_sealed");
    }

    @Test
    @DisplayName("a sensitive file stored before sealing existed is sealed at startup, and still deploys")
    void legacyPlaintextIsSealed() {
        String original = jdbc.queryForObject("select sha256 from config_bundle_files where relative_path = ?",
                String.class, PRIVKEY);
        String plaintext = bundles.findActiveForInstance(instanceId).orElseThrow().files().stream()
                .filter(f -> f.path().equals(PRIVKEY)).findFirst().orElseThrow().content();

        // What a database upgraded from 0.1.2 holds: the row in the clear, which the constraint now
        // refuses, so it is lifted for the length of the test.
        jdbc.execute("ALTER TABLE config_bundle_files DROP CONSTRAINT ck_bundle_file_sensitive_sealed");
        try {
            jdbc.update("update config_bundle_files set content = ?, content_ciphertext = null, content_wrapped_dek = null, "
                    + "content_kek_id = null, content_cipher = null, content_iv = null where relative_path = ?",
                    plaintext, PRIVKEY);

            assertThat(secrets.sealPlaintext()).isEqualTo(1);
            assertThat(secrets.sealPlaintext()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE config_bundle_files ADD CONSTRAINT ck_bundle_file_sensitive_sealed "
                    + "CHECK (NOT sensitive OR content IS NULL)");
        }

        assertThat(jdbc.queryForObject("select content from config_bundle_files where relative_path = ?",
                String.class, PRIVKEY)).isNull();
        BundleFile key = bundles.findActiveForInstance(instanceId).orElseThrow().files().stream()
                .filter(f -> f.path().equals(PRIVKEY)).findFirst().orElseThrow();
        assertThat(key.content()).isEqualTo(plaintext);
        assertThat(key.sha256()).isEqualTo(original);
    }
}
