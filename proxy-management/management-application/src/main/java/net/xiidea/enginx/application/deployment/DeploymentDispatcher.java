package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.deployment.AgentActivation;
import net.xiidea.enginx.domain.deployment.AgentValidationFailedException;
import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.domain.deployment.CertificateMaterialProvider;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentPhase;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.NginxConfigRenderer;
import net.xiidea.enginx.domain.deployment.RenderTarget;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carries out one deployment: render, upload, validate, activate, reload.
 *
 * <p>Rendering happens here rather than when the deployment was requested, so what reaches the
 * host is what the database says right now. Two edits committed moments apart would otherwise
 * each capture the state their author saw, and applying the earlier snapshot second would revert
 * the later edit without any error (architecture risk R2). A per-instance lock, taken by the
 * caller before this runs, keeps two dispatchers from interleaving on one host.
 */
@Service
public class DeploymentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeploymentDispatcher.class);
    private static final String RESOURCE_TYPE = "DEPLOYMENT";

    private final DeploymentRepository deployments;
    private final ConfigBundleRepository bundles;
    private final ProxySiteRepository sites;
    private final NginxInstanceRepository instances;
    private final NginxConfigRenderer renderer;
    private final CertificateMaterialProvider certificates;
    private final NginxAgentPort agent;
    private final PullDeploymentCoordinator pullDeployments;
    private final AuditRecorder audit;
    private final Clock clock;

    public DeploymentDispatcher(DeploymentRepository deployments,
                                ConfigBundleRepository bundles,
                                ProxySiteRepository sites,
                                NginxInstanceRepository instances,
                                NginxConfigRenderer renderer,
                                CertificateMaterialProvider certificates,
                                NginxAgentPort agent,
                                PullDeploymentCoordinator pullDeployments,
                                AuditRecorder audit,
                                Clock clock) {
        this.deployments = deployments;
        this.bundles = bundles;
        this.sites = sites;
        this.instances = instances;
        this.renderer = renderer;
        this.certificates = certificates;
        this.agent = agent;
        this.pullDeployments = pullDeployments;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @return true when the deployment reached a terminal state, false when it should be retried
     */
    @Transactional
    public boolean dispatch(UUID deploymentId) {
        Deployment deployment = deployments.findById(deploymentId).orElse(null);
        if (deployment == null) {
            log.warn("Outbox referenced deployment {}, which no longer exists", deploymentId);
            return true;
        }
        if (deployment.status().isTerminal()) {
            return true;
        }

        NginxInstance instance = instances.findById(deployment.nginxInstanceId()).orElse(null);
        if (instance == null) {
            deployment.failed(DeploymentPhase.RENDER, "The target NGINX instance no longer exists",
                    null, clock.instant());
            deployments.save(deployment);
            return true;
        }

        Instant now = clock.instant();
        ConfigBundle bundle;
        try {
            bundle = resolveBundle(deployment, instance, now);
        } catch (DomainException e) {
            // A render failure is a problem with the configuration itself, so retrying identical
            // state would fail identically.
            deployment.failed(DeploymentPhase.RENDER, e.getMessage(), null, now);
            deployments.save(deployment);
            auditFailure(deployment, e.getMessage());
            return true;
        }

        if (bundle == null) {
            // Nothing to do: what the instance already serves is byte-for-byte what we would
            // send. Reporting success rather than deploying is what makes a redeploy idempotent.
            deployment.skipped(DeploymentPhase.UPLOAD, "The instance already serves this configuration", now);
            deployment.succeeded(now);
            deployments.save(deployment);
            audit.success(AuditAction.DEPLOYMENT_SUCCEEDED, RESOURCE_TYPE, deployment.id(), null,
                    Map.of("result", "NO_CHANGE", "instance", instance.name()));
            return true;
        }

        return applyBundle(deployment, instance, bundle);
    }

    /**
     * @return the bundle to deploy, or null when the instance already serves this configuration
     */
    private ConfigBundle resolveBundle(Deployment deployment, NginxInstance instance, Instant now) {
        ConfigBundle active = bundles.findActiveForInstance(instance.id()).orElse(null);

        if (deployment.trigger() == DeploymentTrigger.ROLLBACK) {
            // A rollback re-activates a bundle that already exists and has already been validated
            // on this host. Re-rendering would defeat the purpose: the point is to return to a
            // known-good configuration, not to a fresh interpretation of current state.
            return bundles.findById(deployment.configBundleId()).orElseThrow();
        }

        learnNginxVersion(instance, now);
        List<ProxySite> deployable = sites.findDeployableForInstance(instance.id(), now);
        ConfigBundle rendered = renderer.render(UUID.randomUUID(), instance.id(), RenderTarget.of(instance),
                bundles.nextSequence(instance.id()), deployable, certificates,
                deployment.createdBy(), now);

        // The no-op shortcut compares what we would send against what the *database* believes the
        // host is serving. That is a safe assumption only while the two agree — and drift is
        // precisely the case where they do not. Skipping it here is what makes a drifted or
        // broken host repairable: without this, a redeploy is silently a no-op at exactly the
        // moment an operator is trying to put the host back.
        //
        // Still never automatic. Nothing redeploys on its own; this only changes what happens
        // once a person decides to, which is the line R4 draws.
        //
        // The same holds after a deployment whose VERIFY found a site not served by what it sent:
        // the files are in place and the database calls them active, but NGINX kept running the
        // configuration before (a reload that failed at runtime, such as a port another process
        // holds). The agent reports that bundle as active, so status polling cannot tell, and
        // the deployment's own record is the only place the platform knows it.
        boolean hostMayNotMatch = instance.status() == InstanceStatus.DEGRADED
                || deployments.findLatestForInstanceExcept(instance.id(), deployment.id())
                        .map(Deployment::verificationFailed).orElse(false);

        if (active != null && active.hasSameContentAs(rendered) && !hostMayNotMatch) {
            deployment.bundleRendered(active.id(), active.id(), now);
            deployments.save(deployment);
            return null;
        }
        if (hostMayNotMatch && active != null && active.hasSameContentAs(rendered)) {
            // Re-send the bundle the database already considers active. The agent re-validates and
            // reloads it without swapping anything, which is harmless on a host that turns out to
            // be fine after all and is the repair on one where the last reload did not take.
            deployment.bundleRendered(active.id(), active.id(), now);
            deployments.save(deployment);
            return active;
        }

        // Content-addressed: an identical bundle that was rendered before is reused rather than
        // duplicated, which keeps the sequence meaningful and the unique hash constraint happy.
        ConfigBundle stored = bundles.findByInstanceAndHash(instance.id(), rendered.contentHash())
                .orElseGet(() -> bundles.save(rendered));

        deployment.bundleRendered(stored.id(), active == null ? null : active.id(), now);
        deployments.save(deployment);
        return stored;
    }

    private boolean applyBundle(Deployment deployment, NginxInstance instance, ConfigBundle bundle) {
        if (instance.connectivityMode().isPull()) {
            // Nothing to dial. The work is queued for the host to collect, and the deployment is
            // carried from here by results arriving rather than by calls going out.
            pullDeployments.begin(deployment, instance, bundle);
            return true;
        }

        Instant now = clock.instant();
        deployment.started(now);
        deployments.save(deployment);

        try {
            agent.stage(instance, bundle, deployment.idempotencyKey());
            deployment.uploaded(clock.instant());

            AgentActivation activation = agent.activate(instance, bundle, deployment.idempotencyKey(), true);

            // The agent only returns success from activate after nginx -t passed, so recording
            // validation here reflects what actually happened on the host.
            deployment.validated(activation.testOutput(), clock.instant());
            deployment.activated(clock.instant());
            deployment.reloaded(activation.nginxVersion(), clock.instant());
            deployment.succeeded(clock.instant());

            // After success, never gating it. See verify() for why this cannot fail a deployment.
            verify(deployment, instance, bundle);

            bundles.markActive(instance.id(), bundle.id());
            instances.save(observed(instance, activation, clock.instant()));
            deployments.save(deployment);

            audit.success(deployment.trigger() == DeploymentTrigger.ROLLBACK
                            ? AuditAction.CONFIGURATION_ROLLED_BACK
                            : AuditAction.DEPLOYMENT_SUCCEEDED,
                    RESOURCE_TYPE, deployment.id(), null,
                    Map.of("instance", instance.name(),
                            "bundleId", bundle.id().toString(),
                            "sequence", String.valueOf(bundle.sequence()),
                            "noop", String.valueOf(activation.noop())));
            return true;

        } catch (AgentValidationFailedException e) {
            // Nothing changed on the host: the previous configuration is still being served.
            // Never retried, because identical bytes fail identically and a person must look.
            deployment.failed(DeploymentPhase.VALIDATE,
                    "NGINX rejected the configuration", e.testOutput(), clock.instant());
            bundles.updateStatus(bundle.id(), BundleStatus.FAILED);
            deployments.save(deployment);
            auditFailure(deployment, e.testOutput());
            return true;

        } catch (AgentException e) {
            if (e.isRetryable()) {
                deployment.deferred(DeploymentPhase.UPLOAD, e.getMessage(), clock.instant());
                deployments.save(deployment);
                return false;
            }
            deployment.failed(DeploymentPhase.ACTIVATE, e.getMessage(), null, clock.instant());
            deployments.save(deployment);
            auditFailure(deployment, e.getMessage());
            return true;
        }
    }

    /**
     * Asks a dialled host which NGINX it runs, when that is not yet known.
     *
     * <p>The grammar is version-dependent, so rendering blind would produce the conservative form
     * now and the modern one at the next deployment — a change of bytes with no change of intent,
     * which turns the first redeploy of an untouched host into a real one. A pull host reports
     * its version when it first calls in, before any work reaches it. Failing to ask is not
     * fatal: the conservative form is valid on every version.
     */
    private void learnNginxVersion(NginxInstance instance, Instant now) {
        if (instance.nginxVersion() != null || instance.connectivityMode().isPull()) {
            return;
        }
        try {
            String version = agent.status(instance).nginxVersion();
            if (version != null && !version.isBlank()) {
                instance.observed(instance.status(), version, instance.agentVersion(), now);
            }
        } catch (RuntimeException e) {
            // The deployment will reach the host or fail on its own terms; this was only a question.
        }
    }

    /**
     * Asks the host whether it actually answers for the names just deployed.
     *
     * <p>A successful reload proves the configuration parsed and loaded. It does not prove a
     * client reaches anything: an upstream may be down, or the site may be shadowed by a
     * server_name collision that NGINX accepts without complaint. This is the only phase that
     * looks at the deployment from the outside.
     *
     * <p>Wrapped in a catch-all because it is diagnostic. A deployment that has already reloaded
     * successfully must not be reported as failed because the probe itself could not run — the
     * configuration is live either way, and saying otherwise would send someone to fix a host
     * that is working.
     */
    private void verify(Deployment deployment, NginxInstance instance, ConfigBundle bundle) {
        try {
            // Each with the marker the deployed bundle renders for it, read from that bundle's own
            // files: a response from anything else on the port, or from an earlier render of the
            // same site, does not count as this deployment being served.
            List<NginxAgentPort.VerifyTarget> targets = NginxConfigRenderer.siteMarkers(bundle).entrySet().stream()
                    .map(entry -> new NginxAgentPort.VerifyTarget(entry.getKey(), entry.getValue()))
                    .toList();

            if (targets.isEmpty()) {
                return;
            }
            deployment.verified(agent.verify(instance, targets), clock.instant());

        } catch (RuntimeException e) {
            deployment.verificationSkipped("The host could not be probed: " + e.getMessage(), clock.instant());
        }
    }

    private static NginxInstance observed(NginxInstance instance, AgentActivation activation, Instant now) {
        instance.observed(net.xiidea.enginx.domain.nginx.InstanceStatus.ONLINE,
                activation.nginxVersion(), null, now);
        return instance;
    }

    private void auditFailure(Deployment deployment, String detail) {
        audit.failure(AuditAction.DEPLOYMENT_FAILED, RESOURCE_TYPE, deployment.id(), detail);
    }
}
