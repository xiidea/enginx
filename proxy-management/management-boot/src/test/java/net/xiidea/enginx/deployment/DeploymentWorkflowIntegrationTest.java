package net.xiidea.enginx.deployment;

import net.xiidea.enginx.application.deployment.ConfigurationPreview;
import net.xiidea.enginx.application.deployment.ConfigurationPreviewService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentEvent;
import net.xiidea.enginx.domain.deployment.DeploymentPhase;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.ConflictException;
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

/**
 * The deployment workflow end to end, minus the wire.
 *
 * <p>The agent is faked, but the outbox, the dispatcher, the rendering, the bundle
 * content-addressing and the phase history are all real and backed by PostgreSQL. What the fake
 * replaces is exactly the part the Go tests cover directly against the filesystem.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
class DeploymentWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private DeploymentService deployments;
    @Autowired
    private ConfigurationPreviewService previews;
    @Autowired
    private OutboxDispatcherJob dispatcher;
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
    private ProxySite site;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate deployments, config_bundles, outbox_messages, permission_grants, "
                + "domain_group_members, domain_groups, proxy_sites, nginx_instances "
                + "restart identity cascade");
        agent.reset();
        caller.actAsSuperAdmin();

        NginxInstance instance = instances.save(NginxInstance.register(UUID.randomUUID(), "nginx-deployments",
                "nginx", "https://nginx:8443", "A".repeat(64), "TEST", Instant.now()));
        instanceId = instance.id();
        site = sites.create(new ProxySiteCommands.Create(specFor("app.example.com"), AdminState.ENABLED));
    }

    @Test
    @DisplayName("a deployment renders, uploads, validates, activates and reloads, in that order")
    void happyPath() {
        Deployment queued = deployments.deploySite(site.id());
        assertThat(queued.status()).isEqualTo(DeploymentStatus.PENDING);
        // Nothing has been rendered yet: the bundle is built by the dispatcher from current state.
        assertThat(queued.configBundleId()).isNull();

        dispatcher.drainOnce();

        Deployment done = deployments.get(queued.id());
        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(done.events()).extracting(event -> event.phase().name())
                .containsSubsequence("RENDER", "UPLOAD", "VALIDATE", "ACTIVATE", "RELOAD");

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        assertThat(active.id()).isEqualTo(done.configBundleId());
        assertThat(active.status()).isEqualTo(BundleStatus.ACTIVE);
    }

    @Test
    @DisplayName("a failed validation never reloads, and leaves the previous bundle active")
    void validationFailureIsSafe() {
        deployAndDrain();
        ConfigBundle originallyActive = bundles.findActiveForInstance(instanceId).orElseThrow();

        // Change something so the next render differs, then have the agent reject it.
        sites.update(new ProxySiteCommands.Update(site.id(),
                specFor("app.example.com", UpstreamTarget.of("http", "10.9.9.9", 9999)), null));
        agent.behave(TestAgent.Behaviour.FAIL_VALIDATION);

        Deployment failed = deployAndDrain();

        assertThat(failed.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(failed.events()).extracting(event -> event.phase().name())
                .doesNotContain("RELOAD");
        assertThat(failed.nginxTestOutput()).contains("host not found in upstream");

        // The host is still serving what it was serving before.
        assertThat(bundles.findActiveForInstance(instanceId).orElseThrow().id())
                .isEqualTo(originallyActive.id());
    }

    @Test
    @DisplayName("the rejected bundle is kept and marked FAILED so the failure can be inspected")
    void rejectedBundleIsRetained() {
        deployAndDrain();
        sites.update(new ProxySiteCommands.Update(site.id(),
                specFor("app.example.com", UpstreamTarget.of("http", "10.9.9.9", 9999)), null));
        agent.behave(TestAgent.Behaviour.FAIL_VALIDATION);

        Deployment failed = deployAndDrain();

        assertThat(bundles.findById(failed.configBundleId()).orElseThrow().status())
                .isEqualTo(BundleStatus.FAILED);
    }

    @Test
    @DisplayName("redeploying unchanged state is a no-op that never touches the agent")
    void unchangedRedeployIsANoop() {
        deployAndDrain();
        int stagesAfterFirst = agent.stageCalls();

        Deployment second = deployAndDrain();

        assertThat(second.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(agent.stageCalls()).isEqualTo(stagesAfterFirst);
        assertThat(second.events()).anyMatch(event -> event.phase() == DeploymentPhase.UPLOAD
                && event.detail() != null && event.detail().contains("already serves"));
        assertThat(countBundles()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transport failure is retried; the deployment stays pending rather than failing")
    void transportFailureIsRetried() {
        agent.behave(TestAgent.Behaviour.UNREACHABLE);
        Deployment queued = deployments.deploySite(site.id());
        dispatcher.drainOnce();

        Deployment afterFirstAttempt = deployments.get(queued.id());
        assertThat(afterFirstAttempt.status()).isEqualTo(DeploymentStatus.PENDING);
        assertThat(afterFirstAttempt.attempt()).isEqualTo(1);

        // The outbox row is scheduled for another attempt rather than discarded.
        String status = jdbc.queryForObject(
                "select status from outbox_messages where aggregate_id = ?", String.class, queued.id());
        assertThat(status).isEqualTo("NEW");

        // Once the agent is reachable, the same deployment completes.
        agent.behave(TestAgent.Behaviour.SUCCEED);
        jdbc.update("update outbox_messages set next_attempt_at = now() - interval '1 minute'");
        dispatcher.drainOnce();

        assertThat(deployments.get(queued.id()).status()).isEqualTo(DeploymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("every attempt reuses one idempotency key, so a lost response cannot double-apply")
    void retriesReuseTheIdempotencyKey() {
        agent.behave(TestAgent.Behaviour.UNREACHABLE);
        Deployment queued = deployments.deploySite(site.id());

        dispatcher.drainOnce();
        jdbc.update("update outbox_messages set next_attempt_at = now() - interval '1 minute'");
        dispatcher.drainOnce();

        assertThat(agent.idempotencyKeys()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(agent.idempotencyKeys()).containsOnly(queued.id().toString());
    }

    @Test
    @DisplayName("a second deployment to the same instance is refused while one is in flight")
    void onlyOneDeploymentPerInstanceAtATime() {
        deployments.deploySite(site.id());

        assertThatThrownBy(() -> deployments.deploySite(site.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    @DisplayName("an expired site leaves the bundle, and its removal is itself a deployment")
    void expiredSitesAreNotRendered() {
        ProxySite second = sites.create(new ProxySiteCommands.Create(
                specFor("temporary.example.com"), AdminState.ENABLED));
        deployAndDrain();
        assertThat(bundles.findActiveForInstance(instanceId).orElseThrow().siteIds()).hasSize(2);

        // Expire it in the past. The renderer reads the window columns, so no lifecycle sweep
        // has to have run for the bundle to be correct.
        jdbc.update("update proxy_sites set expires_at = now() - interval '1 hour' where id = ?", second.id());

        Deployment afterExpiry = deployAndDrain();

        assertThat(afterExpiry.status()).isEqualTo(DeploymentStatus.SUCCESS);
        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        assertThat(active.siteIds()).containsExactly(site.id());
        assertThat(active.files()).noneMatch(file -> file.path().contains("temporary.example.com"));
    }

    @Test
    @DisplayName("a disabled site is not rendered, but the sites around it are unaffected")
    void disabledSitesAreNotRendered() {
        ProxySite second = sites.create(new ProxySiteCommands.Create(
                specFor("other.example.com"), AdminState.ENABLED));
        deployAndDrain();

        sites.disable(second.id());
        deployAndDrain();

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        assertThat(active.siteIds()).containsExactly(site.id());
    }

    @Test
    @DisplayName("rollback re-activates the earlier bundle rather than re-rendering current state")
    void rollbackRestoresTheEarlierBundle() {
        Deployment first = deployAndDrain();
        UUID firstBundle = first.configBundleId();

        sites.update(new ProxySiteCommands.Update(site.id(),
                specFor("app.example.com", UpstreamTarget.of("http", "10.1.1.1", 8080)), null));
        Deployment second = deployAndDrain();
        assertThat(second.configBundleId()).isNotEqualTo(firstBundle);

        Deployment rollback = deployments.rollback(firstBundle);
        dispatcher.drainOnce();

        Deployment done = deployments.get(rollback.id());
        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(done.configBundleId()).isEqualTo(firstBundle);
        assertThat(bundles.findActiveForInstance(instanceId).orElseThrow().id()).isEqualTo(firstBundle);
    }

    @Test
    @DisplayName("preview renders without deploying and reports what would change")
    void previewChangesNothing() {
        deployAndDrain();
        int stagesBefore = agent.stageCalls();

        sites.update(new ProxySiteCommands.Update(site.id(),
                specFor("app.example.com", UpstreamTarget.of("http", "10.2.2.2", 8080)), null));

        ConfigurationPreview preview = previews.previewSite(site.id());

        assertThat(preview.changed()).isTrue();
        assertThat(preview.changedPaths()).anyMatch(path -> path.startsWith("modified: conf.d/app.example.com"));
        assertThat(preview.siteConfiguration()).contains("server 10.2.2.2:8080");
        assertThat(agent.stageCalls()).isEqualTo(stagesBefore);
        assertThat(countBundles()).isEqualTo(1);
    }

    @Test
    @DisplayName("a successful deployment asks the host whether it actually serves the names")
    void verifyRunsAfterReload() {
        Deployment done = deployAndDrain();

        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        // VERIFY comes last, because it can only ask a question the reload has already answered.
        assertThat(done.events()).extracting(event -> event.phase().name())
                .containsSubsequence("RELOAD", "VERIFY");
        // The name is sent as a Host header, so the probe has to be told which names to ask about.
        assertThat(agent.verifiedNames()).contains("app.example.com");
    }

    /**
     * The rule R3 exists to state. A reload that succeeded means the configuration is loaded and
     * being served; a verification failure is usually a dead upstream or a DNS name that does not
     * point here, and reverting the configuration would fix neither while discarding the
     * operator's change.
     */
    @Test
    @DisplayName("a site that does not answer is recorded, and does not fail the deployment")
    void verifyFailureDoesNotFailTheDeployment() {
        agent.sitesRespond(false);

        Deployment done = deployAndDrain();

        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(done.errorMessage()).isNull();

        assertThat(done.events())
                .filteredOn(event -> event.phase() == DeploymentPhase.VERIFY)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.result()).isEqualTo(DeploymentEvent.EventResult.FAILURE);
                    assertThat(event.detail()).contains("no response");
                });

        // And the bundle is still the active one: nothing was rolled back.
        assertThat(bundles.findActiveForInstance(instanceId).orElseThrow().id())
                .isEqualTo(done.configBundleId());
    }

    @Test
    @DisplayName("an answer from another server — a default page — is not the site being served")
    void anAnswerFromAnotherServerIsNotVerified() {
        // The case that made VERIFY report success for a site nobody was serving: a distribution's
        // default page answers every name with 200.
        agent.answerFromAnotherServer(true);

        Deployment done = deployAndDrain();

        assertThat(done.events())
                .filteredOn(event -> event.phase() == DeploymentPhase.VERIFY)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.result()).isEqualTo(DeploymentEvent.EventResult.FAILURE);
                    assertThat(event.detail()).contains("not by this site's configuration");
                });
    }

    @Test
    @DisplayName("an earlier render of the site still answering is reported as the reload not taking effect")
    void anOlderConfigurationStillLiveIsNotVerified() {
        // NGINX accepted the reload signal but kept its old config: the site's own server block
        // answers, with an earlier marker. The site id alone would have called this served.
        agent.serveOlderConfiguration(true);

        Deployment done = deployAndDrain();

        assertThat(done.events())
                .filteredOn(event -> event.phase() == DeploymentPhase.VERIFY)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.result()).isEqualTo(DeploymentEvent.EventResult.FAILURE);
                    assertThat(event.detail()).contains("an older configuration is still live")
                            .contains("the reload did not take effect");
                });
    }

    @Test
    @DisplayName("after a reload that did not take, a redeploy reaches the host again")
    void aRedeployRepairsAReloadThatDidNotTakeEffect() {
        agent.serveOlderConfiguration(true);
        deployAndDrain();

        // Whatever held the port is gone. Nothing about the site changed, so without the failed
        // VERIFY on record this redeploy would be skipped as already served — and the host would
        // keep the old configuration while the platform reported success.
        agent.serveOlderConfiguration(false);
        int activationsBefore = agent.activateCalls();

        Deployment repaired = deployAndDrain();

        assertThat(agent.activateCalls()).isEqualTo(activationsBefore + 1);
        assertThat(repaired.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(repaired.verificationFailed()).isFalse();

        // Once VERIFY has confirmed it, the next identical deployment is a no-op again.
        int activationsAfterRepair = agent.activateCalls();
        deployAndDrain();
        assertThat(agent.activateCalls()).isEqualTo(activationsAfterRepair);
    }

    @Test
    @DisplayName("a served site reports the revision that is live")
    void aServedSiteNamesItsRevision() {
        Deployment done = deployAndDrain();

        assertThat(done.events())
                .filteredOn(event -> event.phase() == DeploymentPhase.VERIFY)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.result()).isEqualTo(DeploymentEvent.EventResult.SUCCESS);
                    assertThat(event.detail()).containsPattern("served \\(v\\d+ [0-9a-f]{12}\\)");
                });
    }

    @Test
    @DisplayName("a host that cannot be probed is skipped, not reported as an outage")
    void unprobeableHostIsSkipped() {
        // Not knowing whether a site answers is a different thing from knowing that it does not,
        // and conflating them would make an agent restart look like a site being down.
        agent.failVerification(true);

        Deployment done = deployAndDrain();

        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(done.events())
                .filteredOn(event -> event.phase() == DeploymentPhase.VERIFY)
                .singleElement()
                .satisfies(event ->
                        assertThat(event.result()).isEqualTo(DeploymentEvent.EventResult.SKIPPED));
    }

    // ---- helpers -----------------------------------------------------------

    private Deployment deployAndDrain() {
        Deployment queued = deployments.deploySite(site.id());
        dispatcher.drainOnce();
        return deployments.get(queued.id());
    }

    private int countBundles() {
        Integer count = jdbc.queryForObject("select count(*) from config_bundles", Integer.class);
        return count == null ? 0 : count;
    }

    private ProxySiteSpec specFor(String domain) {
        return specFor(domain, UpstreamTarget.of("http", "10.10.10.20", 8080));
    }

    private ProxySiteSpec specFor(String domain, UpstreamTarget upstream) {
        return new ProxySiteSpec("Site " + domain, DomainName.of(domain), instanceId,
                TimeWindow.unbounded(), false, false, false, false, null,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(upstream), List.of(), List.of());
    }
}
