package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import net.xiidea.enginx.domain.deployment.NginxConfigRenderer;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.outbox.OutboxMessage;
import net.xiidea.enginx.domain.outbox.OutboxRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Requesting deployments and reading their history.
 *
 * <p>Requesting a deployment does no work beyond recording that it was asked for. The deployment
 * row and its outbox message commit in one local transaction, and the dispatcher takes it from
 * there. Calling the agent inside the request would put a remote call inside a database
 * transaction, and whichever of the two failed would leave the other half applied (AD-5).
 */
@Service
public class DeploymentService {

    private static final String RESOURCE_TYPE = "DEPLOYMENT";

    private final DeploymentRepository deployments;
    private final InstanceLock lock;
    private final ConfigBundleRepository bundles;
    private final ProxySiteRepository sites;
    private final NginxInstanceRepository instances;
    private final SitePermissionService permissions;
    private final OutboxRepository outbox;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public DeploymentService(DeploymentRepository deployments,
                             ConfigBundleRepository bundles,
                             ProxySiteRepository sites,
                             NginxInstanceRepository instances,
                             SitePermissionService permissions,
                             OutboxRepository outbox,
                             AuditRecorder audit,
                             ActorProvider actorProvider,
                             InstanceLock lock,
                             Clock clock) {
        this.lock = lock;
        this.deployments = deployments;
        this.bundles = bundles;
        this.sites = sites;
        this.instances = instances;
        this.permissions = permissions;
        this.outbox = outbox;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    /**
     * Deploys the instance a site belongs to.
     *
     * <p>The unit is the instance, not the site (AD-3), so this necessarily republishes every
     * site on that host. That is safe because the bundle is rendered from current state: the
     * other sites are re-emitted exactly as the database describes them.
     */
    @Transactional
    public Deployment deploySite(UUID siteId) {
        ProxySite site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId));
        permissions.requireSiteAccess(site, PermissionLevel.OPERATE);

        return queue(site.nginxInstanceId(), DeploymentTrigger.MANUAL,
                Map.of("proxySiteId", siteId.toString(), "domain", site.domain().value()));
    }

    /**
     * A deployment that will include whatever the caller's transaction has just saved for this
     * site, or empty when none can be promised now.
     *
     * <p>For a change that deploys itself, where refusing because another deployment is in flight
     * would roll the change back with it. Takes the instance's lock first, so a deployment already
     * rendering finishes before this looks; after that, one still pending has not rendered, and
     * will render after this transaction commits, so it is reused rather than refused. The one case
     * left is a pull host's deployment that has rendered and is waiting for the host: it cannot
     * include the change, a second may not run beside it, and the change stays saved and shown as
     * not yet deployed.
     */
    @Transactional
    public java.util.Optional<Deployment> requestSiteDeployment(UUID siteId) {
        ProxySite site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId));
        permissions.requireSiteAccess(site, PermissionLevel.OPERATE);
        lock.lockForUpdate(site.nginxInstanceId());
        return queueOrJoin(site, "onSave");
    }

    /**
     * The deployment that takes a deleted site off its host, requested in the transaction that
     * deletes it, with the site as it was. Empty when the host was not serving it — never
     * deployed, or disabled and deployed since — since removing it then changes nothing there, or
     * when none can be promised now, for the same reason as {@link #requestSiteDeployment}.
     *
     * <p>Whether the host serves it is read from the active bundle rather than from the site's
     * state: a site disabled and saved without deploying is still being served.
     */
    @Transactional
    public java.util.Optional<Deployment> requestRemovalDeployment(ProxySite deleted) {
        permissions.requireSiteAccess(deleted, PermissionLevel.OPERATE);
        lock.lockForUpdate(deleted.nginxInstanceId());
        String path = NginxConfigRenderer.siteFilePath(deleted);
        boolean served = bundles.findActiveForInstance(deleted.nginxInstanceId())
                .map(bundle -> bundle.files().stream().anyMatch(file -> file.path().equals(path)))
                .orElse(false);
        return served ? queueOrJoin(deleted, "onDelete") : java.util.Optional.empty();
    }

    /** Queues a deployment for the site's instance, or reuses one that has not rendered yet. */
    private java.util.Optional<Deployment> queueOrJoin(ProxySite site, String reason) {
        java.util.Optional<Deployment> active = deployments.findActiveForInstance(site.nginxInstanceId());
        if (active.isEmpty()) {
            return java.util.Optional.of(queue(site.nginxInstanceId(), DeploymentTrigger.MANUAL,
                    Map.of("proxySiteId", site.id().toString(), "domain", site.domain().value(), reason, true)));
        }
        return active.get().status() == DeploymentStatus.PENDING ? active : java.util.Optional.empty();
    }

    @Transactional
    public Deployment deployInstance(UUID instanceId) {
        requireInstance(instanceId);
        // Deploying a whole instance republishes sites the caller may not individually control,
        // so it takes authority over everything rather than over one domain.
        permissions.requireGlobalAdmin();
        return queue(instanceId, DeploymentTrigger.MANUAL, Map.of("nginxInstanceId", instanceId.toString()));
    }

    /**
     * Re-activates a bundle this instance has served before.
     *
     * <p>Never automatic. The only automatic revert in the system is the agent restoring the
     * previous release when a reload fails, which is a refusal to leave the host broken rather
     * than a decision to change versions.
     */
    @Transactional
    public Deployment rollback(UUID targetBundleId) {
        ConfigBundle target = bundles.findById(targetBundleId)
                .orElseThrow(() -> new NotFoundException("CONFIG_BUNDLE", targetBundleId));
        requireInstance(target.nginxInstanceId());
        permissions.requireGlobalAdmin();

        ConfigBundle active = bundles.findActiveForInstance(target.nginxInstanceId()).orElse(null);
        if (active != null && active.id().equals(targetBundleId)) {
            throw new ConflictException("That bundle is already the active configuration");
        }
        requireNoDeploymentInFlight(target.nginxInstanceId());

        Instant now = clock.instant();
        Deployment deployment = Deployment.queueRollback(UUID.randomUUID(), target.nginxInstanceId(),
                targetBundleId, active == null ? null : active.id(), actor(), now);

        Deployment saved = deployments.save(deployment);
        outbox.enqueue(OutboxMessage.forDeployment(saved.id(), now));

        audit.success(AuditAction.DEPLOYMENT_REQUESTED, RESOURCE_TYPE, saved.id(), null,
                Map.of("trigger", "ROLLBACK",
                        "targetBundleId", targetBundleId.toString(),
                        "targetSequence", String.valueOf(target.sequence())));
        return saved;
    }

    /**
     * Queues a deployment on the platform's own behalf, for a scheduled lifecycle transition.
     *
     * <p>No permission check: there is no user here, and the change being published was already
     * authorised when someone set the expiry. Deliberately not reachable from the API — it is
     * called only by the lifecycle sweep.
     *
     * <p>It also skips the one-at-a-time guard that the user-facing paths apply. That guard is a
     * courtesy against pile-ups, and refusing here would be worse than useless: the site's status
     * has just changed in the same transaction, so a refusal would leave the database saying
     * "expired" while NGINX carried on serving it. Queuing a second deployment is harmless
     * because the dispatcher serialises per instance and renders current state, so the later one
     * finds nothing to do and reports a no-op.
     */
    @Transactional
    public Deployment queueSystemDeployment(UUID instanceId, DeploymentTrigger trigger,
                                            Map<String, Object> auditContext) {
        Instant now = clock.instant();
        Deployment saved = deployments.save(
                Deployment.queue(UUID.randomUUID(), instanceId, trigger, "system", now));
        outbox.enqueue(OutboxMessage.forDeployment(saved.id(), now));
        audit.success(AuditAction.DEPLOYMENT_REQUESTED, RESOURCE_TYPE, saved.id(), null, auditContext);
        return saved;
    }

    private Deployment queue(UUID instanceId, DeploymentTrigger trigger, Map<String, Object> auditContext) {
        requireNoDeploymentInFlight(instanceId);

        Instant now = clock.instant();
        Deployment saved = deployments.save(
                Deployment.queue(UUID.randomUUID(), instanceId, trigger, actor(), now));

        // Same transaction as the deployment row. Either both are durable or neither is.
        outbox.enqueue(OutboxMessage.forDeployment(saved.id(), now));

        audit.success(AuditAction.DEPLOYMENT_REQUESTED, RESOURCE_TYPE, saved.id(), null, auditContext);
        return saved;
    }

    /**
     * One deployment at a time per instance.
     *
     * <p>Two concurrent deployments to one host would race over the same symlink, and the loser
     * would leave the database claiming a bundle the host is not serving. Queuing behind the
     * one in flight is the honest answer: the next request will render current state anyway, so
     * nothing is lost by waiting.
     */
    private void requireNoDeploymentInFlight(UUID instanceId) {
        if (deployments.hasActiveDeployment(instanceId)) {
            throw new ConflictException(
                    "A deployment is already in progress for this NGINX instance. Wait for it to finish.");
        }
    }

    @Transactional(readOnly = true)
    public Deployment get(UUID id) {
        Deployment deployment = deployments.findById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
        permissions.requireDeploymentVisibility(deployment.nginxInstanceId());
        return deployment;
    }

    @Transactional(readOnly = true)
    public PageResult<Deployment> search(UUID instanceId, List<DeploymentStatus> statuses, int page, int size) {
        permissions.requireDeploymentVisibility(instanceId);
        return deployments.search(instanceId, statuses, page, size);
    }

    @Transactional(readOnly = true)
    public List<ConfigBundle> recentBundles(UUID instanceId, int limit) {
        permissions.requireDeploymentVisibility(instanceId);
        return bundles.findRecentForInstance(instanceId, limit);
    }

    @Transactional(readOnly = true)
    public ConfigBundle bundle(UUID bundleId) {
        ConfigBundle bundle = bundles.findById(bundleId)
                .orElseThrow(() -> new NotFoundException("CONFIG_BUNDLE", bundleId));
        permissions.requireDeploymentVisibility(bundle.nginxInstanceId());
        return bundle;
    }

    private void requireInstance(UUID instanceId) {
        if (instances.findById(instanceId).isEmpty()) {
            throw new NotFoundException("NGINX_INSTANCE", instanceId);
        }
    }

    private String actor() {
        return actorProvider.currentActor().username();
    }
}
