package net.xiidea.enginx.infrastructure.observability;

import net.xiidea.enginx.application.observability.PlatformStatistics;
import net.xiidea.enginx.application.observability.PlatformStatisticsProvider;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The counters, as aggregate queries.
 *
 * <p>Every query here is a grouped count or a single {@code min}, all covered by the indexes the
 * tables already carry for their own access paths, so no index exists solely to support metrics.
 *
 * <p>Runs in its own read-only transaction: a scrape must not join, extend, or be able to fail a
 * transaction started by whatever else happens to be running.
 */
@Component
public class SqlPlatformStatisticsProvider implements PlatformStatisticsProvider {

    private final EntityManager entityManager;

    public SqlPlatformStatisticsProvider(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PlatformStatistics current(Duration agentSilenceThreshold) {
        Instant now = Instant.now();

        Map<String, Long> sites = countByStatus("proxy_sites");
        Map<String, Long> certificates = countByStatus("certificates");

        long pending = count("select count(*) from deployments where status in ('PENDING','IN_PROGRESS')");
        long failed = count("select count(*) from deployments where status = 'FAILED'");
        long outbox = count("select count(*) from outbox_messages where status in ('NEW','IN_PROGRESS')");
        long dead = count("select count(*) from outbox_messages where status = 'DEAD'");

        // An instance never heard from counts as unreachable too — a registered agent that has
        // never checked in is exactly the case an operator needs to see.
        Instant cutoff = now.minus(agentSilenceThreshold);
        long unreachable = ((Number) entityManager
                .createNativeQuery("select count(*) from nginx_instances "
                        + "where last_seen_at is null or last_seen_at < :cutoff")
                .setParameter("cutoff", cutoff)
                .getSingleResult()).longValue();

        // DEGRADED covers both "answering but not serving" and "serving something we did not
        // deploy". The heartbeat sets it; counting it here is what puts drift on a dashboard.
        long drifted = count("select count(*) from nginx_instances where status = 'DEGRADED'");

        // A notification that reached nobody is worse than one never attempted: the ledger has
        // recorded it as handled, so nothing will try again.
        long notificationsFailed = count("select count(*) from notification_ledger where status = 'FAILED'");

        return new PlatformStatistics(sites, certificates, pending, failed, outbox, dead, unreachable,
                drifted, notificationsFailed, nearestExpiryDays(now));
    }

    /**
     * Days until the soonest expiry among certificates that are still being served. One threshold
     * alert on this one number covers the whole estate, which is why it is preferred to a gauge
     * per certificate — that would put an unbounded, churning label set into the metrics store.
     */
    private long nearestExpiryDays(Instant now) {
        Object soonest = entityManager
                .createNativeQuery("select min(expires_at) from certificates "
                        + "where status in ('VALID','EXPIRING_SOON') and expires_at is not null")
                .getSingleResult();

        if (soonest == null) {
            return -1;
        }
        Instant expiry = toInstant(soonest);
        return expiry == null ? -1 : ChronoUnit.DAYS.between(now, expiry);
    }

    /**
     * The table name is a compile-time constant from this class, never a caller's string. Status
     * values come back as data and are used as map keys, so nothing user-supplied reaches SQL.
     */
    private Map<String, Long> countByStatus(String table) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager
                .createNativeQuery("select status, count(*) from " + table + " group by status")
                .getResultList();

        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private long count(String sql) {
        return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            default -> null;
        };
    }
}
