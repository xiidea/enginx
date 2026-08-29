package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobPayload;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.agent.AgentJobType;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentPhase;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deploying to a host that collects its own work.
 *
 * <p>The push path runs stage, activate and verify as three blocking calls inside one transaction.
 * That cannot survive the connection being inverted: the platform no longer decides when each step
 * happens, so the sequence becomes a state machine advanced by results arriving from the host.
 *
 * <p>Each transition is its own short transaction, which is the honest shape — a deployment now
 * spans however long the host takes to collect its work, and holding a database transaction open
 * across that would be a connection held for an agent's convenience.
 */
@Service
public class PullDeploymentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PullDeploymentCoordinator.class);
    private static final String RESOURCE_TYPE = "DEPLOYMENT";

    private final AgentJobQueue queue;
    private final DeploymentRepository deployments;
    private final ConfigBundleRepository bundles;
    private final NginxInstanceRepository instances;
    private final AuditRecorder audit;
    private final Clock clock;

    public PullDeploymentCoordinator(AgentJobQueue queue, DeploymentRepository deployments,
                                     ConfigBundleRepository bundles, NginxInstanceRepository instances,
                                     AuditRecorder audit, Clock clock) {
        this.queue = queue;
        this.deployments = deployments;
        this.bundles = bundles;
        this.instances = instances;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Starts a deployment by queueing the first job.
     *
     * <p>Returns as soon as the work is queued. The outbox row is done at that point: it exists to
     * guarantee the deployment was started, and from here the deployment's own state carries it.
     */
    @Transactional
    public void begin(Deployment deployment, NginxInstance instance, ConfigBundle bundle) {
        deployment.started(clock.instant());
        deployments.save(deployment);

        queue.enqueue(instance.id(), deployment.id(), AgentJobType.STAGE_BUNDLE,
                AgentJobPayload.forBundle(bundle.id(), deployment.idempotencyKey()));

        log.info("Queued a deployment for pull-mode instance {}; it will collect the work on its next poll",
                instance.name());
    }

    /**
     * Advances the deployment on a result from the host.
     *
     * <p>Every branch here mirrors one in the push dispatcher, deliberately: the deployment record
     * a person reads afterwards must not reveal which transport carried the work.
     */
    @Transactional
    public void onResult(AgentJob job, AgentJobResult result) {
        if (job.deploymentId() == null) {
            return;
        }
        Deployment deployment = deployments.findById(job.deploymentId()).orElse(null);
        if (deployment == null || deployment.status().isTerminal()) {
            return;
        }

        if (!result.succeeded()) {
            fail(deployment, job, result);
            return;
        }

        switch (job.type()) {
            case STAGE_BUNDLE -> {
                deployment.uploaded(clock.instant());
                deployments.save(deployment);
                // Only now, so a host is never told to activate a bundle it has not stored.
                queue.enqueue(job.nginxInstanceId(), deployment.id(), AgentJobType.ACTIVATE_BUNDLE,
                        new AgentJobPayload(job.payload().bundleId(), job.payload().idempotencyKey(), true));
            }
            case ACTIVATE_BUNDLE -> complete(deployment, job, result);
            case DISCARD_BUNDLE -> {
                // Housekeeping, and no part of the deployment's own outcome.
            }
        }
    }

    private void complete(Deployment deployment, AgentJob job, AgentJobResult result) {
        Instant now = clock.instant();

        // The host only reports success after nginx -t passed, so recording validation here
        // reflects what actually happened on it.
        deployment.validated(result.testOutput(), now);
        deployment.activated(now);
        deployment.reloaded(result.nginxVersion(), now);
        deployment.succeeded(now);

        // Verify is a question asked of the host, and questions are not queued work yet. Recorded
        // as skipped rather than passed over: the phase exists to say whether the host actually
        // answers for what was deployed, and quietly omitting it would let a deployment claim a
        // guarantee nobody checked.
        deployment.verificationSkipped(
                "Not supported on a host that collects its own work", now);

        UUID bundleId = job.payload().bundleId();
        bundles.markActive(job.nginxInstanceId(), bundleId);
        deployments.save(deployment);

        Optional<NginxInstance> instance = instances.findById(job.nginxInstanceId());
        instance.ifPresent(host -> {
            host.observed(InstanceStatus.ONLINE, result.nginxVersion(), host.agentVersion(), now);
            instances.save(host);
        });

        audit.success(deployment.trigger() == DeploymentTrigger.ROLLBACK
                        ? AuditAction.CONFIGURATION_ROLLED_BACK
                        : AuditAction.DEPLOYMENT_SUCCEEDED,
                RESOURCE_TYPE, deployment.id(), null,
                Map.of("instance", instance.map(NginxInstance::name).orElse("unknown"),
                        "bundleId", String.valueOf(bundleId),
                        "noop", String.valueOf(result.noop()),
                        "transport", "PULL"));
    }

    private void fail(Deployment deployment, AgentJob job, AgentJobResult result) {
        Instant now = clock.instant();

        if (result.validationFailed()) {
            // Nothing changed on the host: it is still serving what it was. Never retried,
            // because identical bytes fail identically and a person has to look.
            deployment.failed(DeploymentPhase.VALIDATE, "NGINX rejected the configuration",
                    result.testOutput(), now);
            bundles.updateStatus(job.payload().bundleId(), BundleStatus.FAILED);
        } else {
            DeploymentPhase phase = job.type() == AgentJobType.STAGE_BUNDLE
                    ? DeploymentPhase.UPLOAD
                    : DeploymentPhase.ACTIVATE;
            deployment.failed(phase, result.error() == null ? "The host reported a failure" : result.error(),
                    result.testOutput(), now);
        }

        deployments.save(deployment);
        // Otherwise an activation queued behind a failed staging would still be collected, and the
        // host would be told to serve a bundle nobody intends it to.
        queue.cancelPendingFor(deployment.id(), "The deployment failed at an earlier step");

        audit.denied(AuditAction.DEPLOYMENT_FAILED, RESOURCE_TYPE, deployment.id(),
                result.error() == null ? result.testOutput() : result.error());
    }
}
