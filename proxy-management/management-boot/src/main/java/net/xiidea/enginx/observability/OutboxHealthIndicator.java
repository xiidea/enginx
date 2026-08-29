package net.xiidea.enginx.observability;

import net.xiidea.enginx.application.observability.PlatformStatistics;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether committed changes are reaching the hosts.
 *
 * <p>The outbox is where an intent that was accepted by the API waits to become a real change on a
 * server. Depth that keeps growing is the earliest visible symptom of a dispatcher that has stopped
 * or an agent that is refusing, and it is invisible from the API itself — every one of those
 * requests returned 202.
 *
 * <p>Dead messages are called out separately because they are categorically different: they will
 * never be retried, so they need a person, not patience.
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final PlatformStatisticsCache statistics;
    private final ObservabilityProperties properties;

    public OutboxHealthIndicator(PlatformStatisticsCache statistics, ObservabilityProperties properties) {
        this.statistics = statistics;
        this.properties = properties;
    }

    @Override
    public Health health() {
        PlatformStatistics current = statistics.get();
        boolean backedUp = current.outboxDepth() > properties.outboxWarnDepth();
        boolean abandoned = current.outboxDead() > 0;

        Health.Builder health = backedUp || abandoned ? Health.status(PlatformStatus.WARNING) : Health.up();
        return health
                .withDetail("pending", current.outboxDepth())
                .withDetail("dead", current.outboxDead())
                .withDetail("warnDepth", properties.outboxWarnDepth())
                .build();
    }
}
