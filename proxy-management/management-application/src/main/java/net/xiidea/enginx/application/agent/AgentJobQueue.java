package net.xiidea.enginx.application.agent;

import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobPayload;
import net.xiidea.enginx.domain.agent.AgentJobRepository;
import net.xiidea.enginx.domain.agent.AgentJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The work a pull host collects, and the leases that make a dead host harmless.
 *
 * <p>One job outstanding per instance at a time. Two in flight against one NGINX means two
 * processes racing to swap the same symlink, which is what risk R2 asks the platform to prevent —
 * for a push host by serialising the dispatcher, and here by refusing to hand out a second job.
 */
@Service
public class AgentJobQueue {

    private static final Logger log = LoggerFactory.getLogger(AgentJobQueue.class);
    private static final int EXPIRY_SWEEP_LIMIT = 100;

    private final AgentJobRepository jobs;
    private final Duration leaseDuration;
    private final Clock clock;

    public AgentJobQueue(AgentJobRepository jobs,
                         @Value("${enginx.agent.job-lease:5m}") Duration leaseDuration,
                         Clock clock) {
        this.jobs = jobs;
        this.leaseDuration = leaseDuration;
        this.clock = clock;
    }

    @Transactional
    public AgentJob enqueue(UUID instanceId, UUID deploymentId, AgentJobType type, AgentJobPayload payload) {
        AgentJob job = AgentJob.queue(UUID.randomUUID(), instanceId, deploymentId, type, payload,
                clock.instant());
        log.debug("Queued {} for instance {}", type, instanceId);
        return jobs.save(job);
    }

    /**
     * Hands the next job to a host, if it has one waiting and is not already holding one.
     *
     * <p>The lease is short relative to how long the work takes, on purpose: an agent that dies
     * holding a job should return it quickly rather than leaving a deployment stalled for as long
     * as a generous timeout would allow.
     */
    @Transactional
    public Optional<AgentJob> claimNext(UUID instanceId) {
        Instant now = clock.instant();
        // Release first, so a host that died holding a job and came back collects it again on its
        // very next poll rather than waiting for a sweep to notice.
        releaseExpiredLeases();

        return jobs.findNextClaimable(instanceId, now).map(job -> {
            job.lease(leaseDuration, now);
            return jobs.save(job);
        });
    }

    /**
     * Returns jobs whose holder never reported back.
     *
     * <p>Safe to hand out again because every operation is idempotent: bundles are
     * content-addressed and activation carries the deployment's idempotency key, so a job that did
     * in fact complete before the agent vanished replays to the same state.
     *
     * @return how many were released
     */
    @Transactional
    public int releaseExpiredLeases() {
        Instant now = clock.instant();
        List<AgentJob> expired = jobs.findExpiredLeases(now, EXPIRY_SWEEP_LIMIT);

        for (AgentJob job : expired) {
            job.releaseLease(now);
            jobs.save(job);
            log.warn("Lease expired on {} job {} for instance {} after {} attempt(s); requeued",
                    job.type(), job.id(), job.nginxInstanceId(), job.attempts());
        }
        return expired.size();
    }

    /**
     * Recent work for a host, for the console.
     *
     * <p>Authorised the same as viewing the host itself: this is the transport detail of a
     * deployment, and whoever may see the deployment may see why it has not happened.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPERATOR','READ_ONLY')")
    @Transactional(readOnly = true)
    public List<AgentJob> recentFor(UUID instanceId, int limit) {
        return jobs.findRecentForInstance(instanceId, Math.clamp(limit, 1, 50));
    }

    @Transactional(readOnly = true)
    public Optional<AgentJob> find(UUID jobId) {
        return jobs.findById(jobId);
    }

    @Transactional
    public AgentJob save(AgentJob job) {
        return jobs.save(job);
    }

    /**
     * Abandons work queued for a deployment that has already failed.
     *
     * <p>Without this, a deployment whose staging failed would still have its activation waiting,
     * and the host would be told to activate a bundle nobody intends to serve.
     */
    @Transactional
    public void cancelPendingFor(UUID deploymentId, String reason) {
        Instant now = clock.instant();
        for (AgentJob job : jobs.findPendingForDeployment(deploymentId)) {
            job.failed(reason, null, now);
            jobs.save(job);
        }
    }
}
