package net.xiidea.enginx.application.lifecycle;

import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.proxy.SiteStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves sites through their lifecycle as time passes.
 *
 * <p>The sweep is a query over state, not a timer per site. A trigger scheduled for each
 * {@code expires_at} sounds more precise, but editing an expiry would have to reschedule, a
 * delete would have to unschedule, and a single missed misfire would strand a site past its
 * expiry forever. Asking "which sites are now due?" is idempotent and self-healing: a site whose
 * expiry passed while the application was down is picked up by the next run, because the question
 * is about the present rather than about an alarm that may or may not have rung.
 *
 * <p>Precision is bounded by the sweep interval, which is the honest trade. "Expires at 23:59:59"
 * means "stops serving within a minute of 23:59:59", and that is what the API documents.
 */
@Service
public class SiteLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SiteLifecycleService.class);
    private static final String RESOURCE_TYPE = "PROXY_SITE";

    /** Bounded so one run cannot hold locks on the whole table; the next run takes the rest. */
    private static final int BATCH_SIZE = 500;

    private final ProxySiteRepository sites;
    private final DeploymentService deployments;
    private final AuditRecorder audit;
    private final Clock clock;

    public SiteLifecycleService(ProxySiteRepository sites,
                                DeploymentService deployments,
                                AuditRecorder audit,
                                Clock clock) {
        this.sites = sites;
        this.deployments = deployments;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One pass.
     *
     * <p>Everything happens in a single transaction: the status changes, the audit rows and the
     * outbox messages that will republish the affected instances all commit together. If any part
     * fails, none of it happened and the next run tries again — the alternative is a site marked
     * expired in the database while NGINX carries on serving it.
     */
    @Transactional
    public LifecycleSweepResult sweep() {
        Instant now = clock.instant();
        List<ProxySite> due = sites.claimSitesDueForLifecycle(now, BATCH_SIZE);
        if (due.isEmpty()) {
            return LifecycleSweepResult.empty();
        }

        int expired = 0;
        int activated = 0;
        // Ordered so the deployments queued at the end are deterministic, which makes the tests
        // and the audit trail easier to follow.
        Map<UUID, DeploymentTrigger> instancesToRepublish = new LinkedHashMap<>();

        for (ProxySite site : due) {
            SiteStatus before = site.status();

            // The deployment result is not consulted here: a site whose last deployment failed is
            // in ERROR, and its expiry must still take effect. Passing false asks only "what does
            // the clock say", which is the question this sweep exists to answer.
            if (!site.refreshStatus(now, false)) {
                continue;
            }
            SiteStatus after = site.status();
            sites.save(site);

            if (after == SiteStatus.EXPIRED) {
                expired++;
                instancesToRepublish.putIfAbsent(site.nginxInstanceId(), DeploymentTrigger.EXPIRATION);
                audit.success(AuditAction.PROXY_SITE_EXPIRED, RESOURCE_TYPE, site.id(),
                        Map.of("status", before.name()),
                        Map.of("status", after.name(), "expiresAt", String.valueOf(site.window().expiresAt())));
            } else if (after == SiteStatus.ACTIVE) {
                activated++;
                instancesToRepublish.putIfAbsent(site.nginxInstanceId(), DeploymentTrigger.ACTIVATION);
                audit.success(AuditAction.PROXY_SITE_ACTIVATED, RESOURCE_TYPE, site.id(),
                        Map.of("status", before.name()),
                        Map.of("status", after.name(), "activeFrom", String.valueOf(site.window().activeFrom())));
            }
        }

        instancesToRepublish.forEach((instanceId, trigger) ->
                deployments.queueSystemDeployment(instanceId, trigger,
                        Map.of("reason", trigger.name(), "sweptAt", now.toString())));

        LifecycleSweepResult result = new LifecycleSweepResult(due.size(), expired, activated,
                instancesToRepublish.size());
        if (result.changedAnything()) {
            log.info("Lifecycle sweep: {} expired, {} activated, {} instance(s) queued for redeployment",
                    expired, activated, instancesToRepublish.size());
        }
        return result;
    }
}
