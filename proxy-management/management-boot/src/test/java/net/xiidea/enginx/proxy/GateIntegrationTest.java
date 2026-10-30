package net.xiidea.enginx.proxy;

import net.xiidea.enginx.api.gate.GateRelayController;
import net.xiidea.enginx.application.certificate.CertificateCommands;
import net.xiidea.enginx.application.certificate.CertificateService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.gate.GateKeyDerivation;
import net.xiidea.enginx.application.gate.GateKeys;
import net.xiidea.enginx.application.gate.GateRelayService;
import net.xiidea.enginx.application.gate.GateRelayService.Outcome;
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
import net.xiidea.enginx.support.TestOtpMailer;
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

/** Email sign-in from the platform's side: keys, the gate's file, and mailing codes for a gate. */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class, TestCertificateProvider.Config.class,
        TestOtpMailer.Config.class})
@TestPropertySource(properties = "enginx.gate.relay-url=https://enginx.example.com/api/v1/agents/gate/otp-mail")
class GateIntegrationTest extends AbstractIntegrationTest {

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
    private GateRelayService relay;
    @Autowired
    private TestOtpMailer mailer;
    @Autowired
    private TestCertificateProvider authority;
    @Autowired
    private TestAgent agent;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private GateRelayController gateEndpoint;

    private UUID instanceId;
    private UUID certificateId;
    private ProxySite site;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate certificates, acme_accounts, deployments, config_bundles, outbox_messages, "
                + "permission_grants, domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        authority.reset();
        agent.reset();
        mailer.reset();
        caller.actAsSuperAdmin();

        instanceId = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-gate", "nginx",
                "https://nginx:8443", "A".repeat(64), "TEST", Instant.now())).id();
        certificateId = certificates.requestAcme(new CertificateCommands.RequestAcme(
                "cert for " + DOMAIN, List.of(DOMAIN), true, 30)).id();
        site = sites.create(new ProxySiteCommands.Create(spec(), AdminState.ENABLED,
                emailOtp(List.of("*@example.com", "alice@partner.org"), 3600)));
    }

    @Test
    @DisplayName("a gated site is given a key, and its policy reads back as saved")
    void creatingAGatedSiteIssuesAKey() {
        assertThat(keys.reveal(site.id())).get().extracting(key -> key.length).isEqualTo(32);
        ProxySite reloaded = sites.get(site.id());
        assertThat(reloaded.spec().protection().mode()).isEqualTo(ProtectionMode.EMAIL_OTP);
        assertThat(reloaded.spec().protection().emailOtp().allowedEmails())
                .extracting(e -> e.value()).containsExactly("*@example.com", "alice@partner.org");
        assertThat(reloaded.spec().protection().emailOtp().sessionTtl().toSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("the key is stored sealed, never as the base64 the gate is sent")
    void theKeyIsEncryptedAtRest() {
        String encoded = java.util.Base64.getEncoder().encodeToString(keys.reveal(site.id()).orElseThrow());
        byte[] stored = jdbc.queryForObject(
                "select key_ciphertext from proxy_site_gate_secrets where proxy_site_id = ?", byte[].class, site.id());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(encoded);
    }

    @Test
    @DisplayName("the deployed bundle carries the gate's file, with the key the platform holds")
    void theGateFileIsDeployed() {
        Deployment deployment = deployments.deploySite(site.id());
        dispatcher.drainOnce();
        assertThat(deployments.get(deployment.id()).status()).isEqualTo(DeploymentStatus.SUCCESS);

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        BundleFile gate = active.files().stream()
                .filter(file -> file.path().equals("gate/sites/" + DOMAIN + ".json"))
                .findFirst().orElseThrow();
        String key = java.util.Base64.getEncoder().encodeToString(keys.reveal(site.id()).orElseThrow());
        assertThat(gate.content()).contains(key).contains("https://enginx.example.com/api/v1/agents/gate/otp-mail");
        assertThat(gate.access()).isEqualTo(BundleFile.Access.WORKER_READABLE);
    }

    @Test
    @DisplayName("signing everyone out replaces the key")
    void signOutRotatesTheKey() {
        byte[] before = keys.reveal(site.id()).orElseThrow();

        sites.signOutEveryone(site.id());

        assertThat(keys.reveal(site.id()).orElseThrow()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a signed request for an allowed address is mailed, and audited without the code")
    void anAuthenticRequestIsMailed() {
        assertThat(relay.sendCode(signed("Bob@Example.com", "123456", now()))).isEqualTo(Outcome.SENT);

        assertThat(mailer.sent()).containsExactly(new TestOtpMailer.Sent("bob@example.com", DOMAIN, "123456"));
        String after = jdbc.queryForObject("select after_state::text from audit_logs where action = 'GATE_CODE_SENT'",
                String.class);
        assertThat(after).contains("bob@example.com").doesNotContain("123456");
    }

    @Test
    @DisplayName("a bad signature, a stale timestamp or an address off the list sends nothing")
    void inauthenticOrDisallowedRequestsAreRefused() {
        GateRelayService.CodeRequest good = signed("bob@example.com", "123456", now());
        assertThat(relay.sendCode(new GateRelayService.CodeRequest(good.siteId(), good.email(), "654321",
                good.timestamp(), good.signature()))).isEqualTo(Outcome.REFUSED);
        assertThat(relay.sendCode(signed("bob@example.com", "123456", now() - 3600))).isEqualTo(Outcome.REFUSED);
        assertThat(relay.sendCode(signed("mallory@evil.org", "123456", now()))).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    @DisplayName("an address can be sent only so many codes")
    void codesAreRateLimitedPerAddress() {
        for (int i = 0; i < 5; i++) {
            assertThat(relay.sendCode(signed("carol@example.com", "12345" + i, now()))).isEqualTo(Outcome.SENT);
        }
        assertThat(relay.sendCode(signed("carol@example.com", "123459", now()))).isEqualTo(Outcome.RATE_LIMITED);
    }

    @Test
    void withoutMailTheRelaySaysSo() {
        mailer.available(false);
        assertThat(relay.sendCode(signed("dave@example.com", "123456", now()))).isEqualTo(Outcome.MAIL_UNAVAILABLE);
    }

    @Test
    @DisplayName("a request signed with a key from before sign-out-everyone is refused")
    void anOldKeyStopsWorkingAfterRotation() {
        GateRelayService.CodeRequest beforeRotation = signed("erin@example.com", "123456", now());
        sites.signOutEveryone(site.id());
        assertThat(relay.sendCode(beforeRotation)).isEqualTo(Outcome.REFUSED);
    }

    // ---- helpers -----------------------------------------------------------

    private GateRelayService.CodeRequest signed(String email, String code, long timestamp) {
        byte[] key = keys.reveal(site.id()).orElseThrow();
        return new GateRelayService.CodeRequest(site.id(), email, code, timestamp,
                GateKeyDerivation.relaySignature(key, site.id().toString(), email, code, timestamp));
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static ProtectionInput emailOtp(List<String> allowed, Integer ttl) {
        return new ProtectionInput(ProtectionMode.EMAIL_OTP, List.of(), new ProtectionInput.EmailOtp(allowed, ttl));
    }

    private ProxySiteSpec spec() {
        return new ProxySiteSpec("Site " + DOMAIN, DomainName.of(DOMAIN), instanceId,
                TimeWindow.unbounded(), true, true, false, false, certificateId,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of());
    }

    // ---- events from the gate -------------------------------------------------------------

    private String eventsPayload(String batchId, long sentAt, String events) {
        return "{\"siteId\":\"" + site.id() + "\",\"batchId\":\"" + batchId + "\",\"sentAt\":" + sentAt
                + ",\"dropped\":0,\"events\":[" + events + "]}";
    }

    private void sendEvents(String payload, String signature) {
        gateEndpoint.recordEvents(new GateRelayController.EventsRequest(
                List.of(new GateRelayController.EventSection(site.id(), payload, signature))));
    }

    private String sign(String payload) {
        return GateKeyDerivation.eventsSignature(keys.reveal(site.id()).orElseThrow(), payload);
    }

    private List<java.util.Map<String, Object>> gateRows() {
        return jdbc.queryForList("select action, actor_subject, actor_username, ip_address, result, occurred_at, "
                + "after_state::text as after_state from audit_logs where action like 'GATE_%' "
                + "and action <> 'GATE_CODE_SENT' and resource_id = ? order by occurred_at", site.id());
    }

    @Test
    @DisplayName("what the gate saw is audited, each event as the visitor who did it, when it happened")
    void gateEventsAreAudited() {
        long now = Instant.now().getEpochSecond();
        long at = (now - 60) * 1000;
        String payload = eventsPayload("batch-0001", now,
                "{\"at\":" + at + ",\"kind\":\"sign-in-failed\",\"method\":\"code\",\"email\":\"bob@example.com\","
                        + "\"ip\":\"203.0.113.9\",\"reason\":\"wrong-code\"},"
                        + "{\"at\":" + (at + 1000) + ",\"kind\":\"signed-in\",\"method\":\"code\",\"email\":\"bob@example.com\","
                        + "\"ip\":\"203.0.113.9\"},"
                        + "{\"at\":" + (at + 2000) + ",\"kind\":\"access-denied\",\"method\":\"code\",\"email\":\"eve@evil.example\","
                        + "\"ip\":\"198.51.100.7\",\"reason\":\"not-on-list\"}");
        sendEvents(payload, sign(payload));

        List<java.util.Map<String, Object>> rows = gateRows();
        assertThat(rows).extracting(row -> row.get("action"))
                .containsExactly("GATE_SIGN_IN_FAILED", "GATE_SIGNED_IN", "GATE_ACCESS_DENIED");
        assertThat(rows).extracting(row -> row.get("result")).containsExactly("FAILURE", "SUCCESS", "DENIED");
        assertThat(rows).extracting(row -> row.get("actor_subject")).containsOnly("gate");
        assertThat(rows.get(1).get("actor_username")).isEqualTo("bob@example.com");
        assertThat(rows.get(1).get("ip_address")).isEqualTo("203.0.113.9");
        assertThat(((java.sql.Timestamp) rows.get(0).get("occurred_at")).toInstant()).isEqualTo(Instant.ofEpochMilli(at));
        assertThat((String) rows.get(2).get("after_state")).contains("not-on-list").contains(DOMAIN);
    }

    @Test
    @DisplayName("a section not signed with the site's key, stale, or replayed records nothing")
    void forgedStaleAndReplayedEventsAreSkipped() {
        long now = Instant.now().getEpochSecond();
        String event = "{\"at\":" + now * 1000 + ",\"kind\":\"signed-in\",\"method\":\"code\",\"email\":\"mallory@example.com\"}";

        String forged = eventsPayload("batch-forged", now, event);
        sendEvents(forged, GateKeyDerivation.eventsSignature(new byte[32], forged));
        String stale = eventsPayload("batch-stale1", now - 3600, event);
        sendEvents(stale, sign(stale));
        // A code request's signature is not an events signature.
        String crossed = eventsPayload("batch-cross", now, event);
        sendEvents(crossed, GateKeyDerivation.relaySignature(keys.reveal(site.id()).orElseThrow(),
                site.id().toString(), "mallory@example.com", "123456", now));
        assertThat(gateRows()).isEmpty();

        String once = eventsPayload("batch-once1", now, event);
        sendEvents(once, sign(once));
        sendEvents(once, sign(once));
        assertThat(gateRows()).hasSize(1);
    }

    @Test
    @DisplayName("what a visitor typed is bounded and cleaned, and an address that is not one is dropped")
    void visitorTextIsCleaned() {
        long now = Instant.now().getEpochSecond();
        String payload = eventsPayload("batch-clean", now,
                "{\"at\":" + now * 1000 + ",\"kind\":\"sign-in-failed\",\"method\":\"password\","
                        + "\"user\":\"al\\u0000ice\\n" + "x".repeat(300) + "\",\"ip\":\"<script>\",\"reason\":\"Wrong Password!\"},"
                        + "{\"at\":" + now * 1000 + ",\"kind\":\"made-up\",\"method\":\"code\"}");
        sendEvents(payload, sign(payload));

        List<java.util.Map<String, Object>> rows = gateRows();
        assertThat(rows).hasSize(1);
        String user = (String) rows.getFirst().get("actor_username");
        assertThat(user).startsWith("alicexxx").hasSizeLessThanOrEqualTo(128).doesNotContain("\u0000");
        assertThat(rows.getFirst().get("ip_address")).isNull();
        assertThat((String) rows.getFirst().get("after_state")).doesNotContain("Wrong Password");
    }

}
