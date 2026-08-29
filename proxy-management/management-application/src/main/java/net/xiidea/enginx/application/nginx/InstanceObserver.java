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
        try {
            AgentStatus status = agent.status(instance);

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
            return true;
        } catch (RuntimeException e) {
            markOffline(instance);
            log.warn("Instance {} ({}) did not answer: {}", instance.name(), instance.hostname(), e.toString());
            return false;
        }
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
