package net.xiidea.enginx.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Thresholds for metrics and health.
 *
 * <p>Configurable because the right value depends on the estate: a fleet with a five-minute agent
 * heartbeat and one with a thirty-second heartbeat disagree about when silence means trouble.
 *
 * @param statisticsCacheTtl   how long a counter snapshot is reused. Bounds the cost of a scrape
 *                             interval set aggressively, and of a health endpoint polled by both
 *                             an orchestrator and a load balancer.
 * @param agentSilence         how long an instance may go unheard-from before it is unreachable
 * @param outboxWarnDepth      undispatched messages above which health degrades
 * @param certificateWarnDays  days-to-expiry at or below which health degrades
 */
@ConfigurationProperties(prefix = "enginx.observability")
public record ObservabilityProperties(
        Duration statisticsCacheTtl,
        Duration agentSilence,
        long outboxWarnDepth,
        long certificateWarnDays) {

    public ObservabilityProperties {
        statisticsCacheTtl = statisticsCacheTtl == null ? Duration.ofSeconds(15) : statisticsCacheTtl;
        agentSilence = agentSilence == null ? Duration.ofMinutes(5) : agentSilence;
        outboxWarnDepth = outboxWarnDepth <= 0 ? 25 : outboxWarnDepth;
        certificateWarnDays = certificateWarnDays <= 0 ? 7 : certificateWarnDays;
    }
}
