package net.xiidea.enginx.observability;

import net.xiidea.enginx.application.observability.PlatformStatistics;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether the agents are being heard from.
 *
 * <p>Reads the recorded last-seen time rather than calling each agent: a health endpoint that
 * fans out over mTLS to every host in the estate turns one poll into a burst of connections, and
 * makes this endpoint's latency the sum of the slowest agent's.
 */
@Component("agents")
public class AgentConnectivityHealthIndicator implements HealthIndicator {

    private final PlatformStatisticsCache statistics;
    private final ObservabilityProperties properties;

    public AgentConnectivityHealthIndicator(PlatformStatisticsCache statistics,
                                            ObservabilityProperties properties) {
        this.statistics = statistics;
        this.properties = properties;
    }

    @Override
    public Health health() {
        PlatformStatistics current = statistics.get();
        long unreachable = current.instancesUnreachable();
        long degraded = current.instancesDrifted();

        Health.Builder health = unreachable == 0 && degraded == 0
                ? Health.up()
                : Health.status(PlatformStatus.WARNING);
        return health
                .withDetail("unreachable", unreachable)
                // Answering, but either not serving or serving a configuration nobody deployed.
                .withDetail("degraded", degraded)
                .withDetail("silenceThreshold", properties.agentSilence().toString())
                .build();
    }
}
