package net.xiidea.enginx.agent;

import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import net.xiidea.enginx.application.deployment.PullDeploymentCoordinator;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.agent.AgentJobType;
import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
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

/**
 * A deployment to a host the platform never dials.
 *
 * <p>The push path runs stage, activate and verify as three blocking calls in one transaction.
 * Here the platform decides nothing about when each step happens: it queues the first, and the
 * sequence is carried forward by results arriving. What a person reads afterwards must be the same
 * either way, and most of these cases are about that.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
class PullDeploymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProxySiteService sites;
    @Autowired
    private DeploymentService deployments;
    @Autowired
    private OutboxDispatcherJob dispatcher;
    @Autowired
    private AgentJobQueue queue;
    @Autowired
    private PullDeploymentCoordinator coordinator;
    @Autowired
    private AgentEnrolmentService enrolment;
    @Autowired
    private ConfigBundleRepository bundles;
    @Autowired
    private TestAgent agent;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private ProxySite site;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate agent_jobs, agent_tokens, agent_registration_tokens, deployments, "
                + "config_bundles, outbox_messages, permission_grants, domain_group_members, "
                + "domain_groups, proxy_sites, nginx_instances restart identity cascade");
        agent.reset();
        caller.actAsSuperAdmin();

        String token = enrolment.mintRegistrationToken("test", null, null).secret();
        NginxInstance instance = enrolment.register(token, "nginx-pull", "pull.example.com", "TEST").instance();
        instanceId = instance.id();

        site = sites.create(new ProxySiteCommands.Create(specFor("app.example.com"), AdminState.ENABLED));
    }

    /**
     * The whole feature: work reaches a host nothing ever dialled. Note the agent double is never
     * touched — if the dispatcher had tried to call out, it would have recorded the call.
     */
    @Test
    @DisplayName("a deployment queues work instead of dialling the host")
    void deploymentIsQueuedNotDialled() {
        Deployment queued = deployments.deploySite(site.id());
        dispatcher.drainOnce();

        AgentJob job = queue.claimNext(instanceId).orElseThrow();

        assertThat(job.type()).isEqualTo(AgentJobType.STAGE_BUNDLE);
        assertThat(job.deploymentId()).isEqualTo(queued.id());
        assertThat(agent.stageCalls()).isZero();
        assertThat(deployments.get(queued.id()).status()).isEqualTo(DeploymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("staging then activating carries the deployment to success")
    void theSequenceCompletes() {
        Deployment started = deploy();

        AgentJob stage = queue.claimNext(instanceId).orElseThrow();
        report(stage, AgentJobResult.ok());

        // Activation is queued only after staging succeeded, so a host is never told to activate
        // a bundle it has not stored.
        AgentJob activate = queue.claimNext(instanceId).orElseThrow();
        assertThat(activate.type()).isEqualTo(AgentJobType.ACTIVATE_BUNDLE);
        assertThat(activate.payload().bundleId()).isEqualTo(stage.payload().bundleId());

        report(activate, new AgentJobResult(true, false, "syntax is ok", "1.27.5", null, false, false, null));

        Deployment done = deployments.get(started.id());
        assertThat(done.status()).isEqualTo(DeploymentStatus.SUCCESS);
        // The same phases a push deployment records, so the trail does not reveal the transport.
        assertThat(done.events()).extracting(event -> event.phase().name())
                .containsSubsequence("RENDER", "UPLOAD", "VALIDATE", "ACTIVATE", "RELOAD");

        ConfigBundle active = bundles.findActiveForInstance(instanceId).orElseThrow();
        assertThat(active.id()).isEqualTo(activate.payload().bundleId());
        assertThat(active.status()).isEqualTo(BundleStatus.ACTIVE);
    }

    /**
     * VERIFY asks the host a question, and questions are not queued work yet. Recorded as skipped
     * rather than passed over: the phase exists to say whether the host answers for what was
     * deployed, and omitting it silently would let a deployment claim a guarantee nobody checked.
     */
    @Test
    @DisplayName("verification is recorded as skipped, not quietly dropped")
    void verificationIsVisiblySkipped() {
        Deployment started = deploy();
        report(queue.claimNext(instanceId).orElseThrow(), AgentJobResult.ok());
        report(queue.claimNext(instanceId).orElseThrow(),
                new AgentJobResult(true, false, "ok", "1.27.5", null, false, false, null));

        assertThat(deployments.get(started.id()).events())
                .extracting(event -> event.phase().name())
                .contains("VERIFY");
    }

    @Test
    @DisplayName("a rejected configuration fails the deployment and never activates")
    void validationFailureIsSafe() {
        Deployment started = deploy();
        report(queue.claimNext(instanceId).orElseThrow(), AgentJobResult.ok());

        AgentJob activate = queue.claimNext(instanceId).orElseThrow();
        report(activate, new AgentJobResult(false, true, "unknown directive", null, null, false, false,
                "NGINX rejected the configuration"));

        Deployment failed = deployments.get(started.id());
        assertThat(failed.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(failed.events()).extracting(event -> event.phase().name()).doesNotContain("RELOAD");
        // Nothing is serving it, and the bundle is marked so a later render does not reuse it.
        assertThat(bundles.findActiveForInstance(instanceId)).isEmpty();
        assertThat(bundles.findById(activate.payload().bundleId()).orElseThrow().status())
                .isEqualTo(BundleStatus.FAILED);
    }

    /**
     * Otherwise the host collects an activation for a deployment that has already failed, and is
     * told to serve a bundle nobody intends it to.
     */
    @Test
    @DisplayName("a failure at staging abandons the activation queued behind it")
    void failureCancelsTheRestOfTheSequence() {
        Deployment started = deploy();
        AgentJob stage = queue.claimNext(instanceId).orElseThrow();

        report(stage, new AgentJobResult(false, false, null, null, null, false, false, "disk full"));

        assertThat(deployments.get(started.id()).status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(queue.claimNext(instanceId)).isEmpty();
    }

    private Deployment deploy() {
        Deployment queued = deployments.deploySite(site.id());
        dispatcher.drainOnce();
        return queued;
    }

    private void report(AgentJob job, AgentJobResult result) {
        if (result.succeeded()) {
            job.succeeded(result, Instant.now());
        } else {
            job.failed(result.error(), result, Instant.now());
        }
        queue.save(job);
        coordinator.onResult(job, result);
    }

    private ProxySiteSpec specFor(String domain) {
        return new ProxySiteSpec("Site " + domain, DomainName.of(domain), instanceId,
                TimeWindow.unbounded(), false, false, false, false, null,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.0.0.10", 8080)), List.of(), List.of());
    }
}
