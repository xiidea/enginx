package net.xiidea.enginx.observability;

import net.xiidea.enginx.application.observability.PlatformStatistics;
import net.xiidea.enginx.application.observability.PlatformStatisticsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One database read shared by every gauge and every health indicator.
 *
 * <p>Without this, a single Prometheus scrape would issue a query per meter — around a dozen — and
 * a health check polled by an orchestrator would add more. Both endpoints are called on a timer by
 * machines, so the naive version scales its cost with how carefully the platform is watched.
 */
@Component
@EnableConfigurationProperties(ObservabilityProperties.class)
public class PlatformStatisticsCache {

    private static final Logger log = LoggerFactory.getLogger(PlatformStatisticsCache.class);

    private final PlatformStatisticsProvider provider;
    private final ObservabilityProperties properties;
    private final Clock clock;

    private final AtomicReference<PlatformStatistics> value = new AtomicReference<>(PlatformStatistics.empty());
    private volatile Instant readAt = Instant.MIN;
    private volatile boolean stale = true;

    public PlatformStatisticsCache(PlatformStatisticsProvider provider,
                                   ObservabilityProperties properties,
                                   Clock clock) {
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
    }

    public PlatformStatistics get() {
        Instant now = clock.instant();
        if (!stale && Duration.between(readAt, now).compareTo(properties.statisticsCacheTtl()) < 0) {
            return value.get();
        }
        try {
            value.set(provider.current(properties.agentSilence()));
            readAt = now;
            stale = false;
        } catch (RuntimeException e) {
            // Serving the previous snapshot beats throwing out of a metrics scrape or a health
            // probe. The staleness is reported separately, so a database that has gone away shows
            // up as unhealthy rather than as counters that quietly stop moving.
            stale = true;
            log.warn("Could not refresh platform statistics: {}", e.toString());
        }
        return value.get();
    }

    /** Whether the last refresh attempt failed. */
    public boolean isStale() {
        return stale;
    }
}
