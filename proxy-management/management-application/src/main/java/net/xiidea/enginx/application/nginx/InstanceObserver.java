package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Polls one agent and records what it said.
 *
 * <p>A bean of its own rather than a method on {@link InstanceHeartbeatService}, because
 * {@code @Transactional} is applied by a proxy and a self-invoked call never passes through one.
 * Written as a private method on the caller, the REQUIRES_NEW below would silently do nothing,
 * and every instance would share one transaction again — the failure this class exists to prevent.
 */
@Component
public class InstanceObserver {

    private static final Logger log = LoggerFactory.getLogger(InstanceObserver.class);

    private final NginxInstanceRepository instances;
    private final NginxAgentPort agent;
    private final DriftDetector drift;
    private final Clock clock;

    public InstanceObserver(NginxInstanceRepository instances, NginxAgentPort agent,
                            DriftDetector drift, Clock clock) {
        this.instances = instances;
        this.agent = agent;
        this.drift = drift;
        this.clock = clock;
    }

    /**
     * One instance, in its own transaction, so an unreachable host cannot roll back the
     * observations of every host polled before it.
     *
     * @return whether the agent answered
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean poll(NginxInstance instance) {
        if (instance.connectivityMode().isPull()) {
            // Nothing to dial. A pull host reports itself, and its silence is judged elsewhere by
            // how long ago it last called in.
            return instance.status() == InstanceStatus.ONLINE;
        }
        try {
            record(instance, agent.status(instance));
            return true;
        } catch (RuntimeException e) {
            markOffline(instance);
            log.warn("Instance {} ({}) did not answer: {}", instance.name(), instance.hostname(), e.toString());
            return false;
        }
    }

    /**
     * Applies a status to an instance, however it was obtained.
     *
     * <p>Shared between the push poll above and a pull host's own heartbeat on purpose. What a
     * status <em>means</em> — whether the host counts as serving, whether it has drifted — must not
     * depend on which end of the connection asked, or the two models would slowly disagree about
     * what healthy is.
     *
     * <p>Runs in the caller's transaction, deliberately unannotated. {@link #poll} calls it
     * directly, and a {@code @Transactional} here would be a proxy annotation that self-invocation
     * bypasses — an isolation guarantee that reads as present and is not.
     */
    public void record(NginxInstance instance, AgentStatus status) {
        // Reachable but not serving is its own state. Reporting DEGRADED rather than ONLINE is
        // what stops a host with a dead NGINX from counting as healthy merely because the
        // agent beside it still answers.
        boolean serving = status.nginxRunning() && status.configTestOk();

        // Serving the wrong configuration is also DEGRADED. The host is up and answering, so
        // OFFLINE would be a lie, but what it serves is not what the platform believes it put
        // there, and treating that as healthy is what lets drift go unnoticed indefinitely.
        Optional<DriftDetector.Drift> drifted = drift.detect(instance, status);
        drifted.ifPresent(drift::record);

        InstanceStatus observed = serving && drifted.isEmpty()
                ? InstanceStatus.ONLINE
                : InstanceStatus.DEGRADED;

        instance.observed(observed, status.nginxVersion(), status.agentVersion(), clock.instant());
        instances.save(instance);
    }

    /**
     * Marks a pull host offline once it has gone quiet for longer than the threshold.
     *
     * <p>The mirror image of a failed dial: a host that calls in is proving it is there, so the
     * absence of calls is the only evidence available that it is not.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSilence(NginxInstance instance, java.time.Duration threshold) {
        Instant lastSeen = instance.lastSeenAt();
        boolean overdue = lastSeen == null || lastSeen.plus(threshold).isBefore(clock.instant());
        if (overdue) {
            markOffline(instance);
        }
        return !overdue;
    }

    /**
     * Records the status change without touching {@code lastSeenAt}.
     *
     * <p>That field means "when we last heard from it". Moving it on a failed call would erase the
     * one piece of information the staleness check reads, and every unreachable host would look
     * freshly contacted.
     */
    private void markOffline(NginxInstance instance) {
        if (instance.status() == InstanceStatus.OFFLINE) {
            return;
        }
        try {
            instance.observed(InstanceStatus.OFFLINE, instance.nginxVersion(), instance.agentVersion(),
                    instance.lastSeenAt() == null ? clock.instant() : instance.lastSeenAt());
            instances.save(instance);
        } catch (RuntimeException e) {
            // Losing an optimistic-lock race here means something else just wrote the row, which
            // is a fresher observation than this one. Nothing to recover.
            log.debug("Could not record {} as offline: {}", instance.name(), e.toString());
        }
    }
}
