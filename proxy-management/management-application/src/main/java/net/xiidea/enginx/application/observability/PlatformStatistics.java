package net.xiidea.enginx.application.observability;

import java.util.Map;

/**
 * A point-in-time count of the things worth alerting on.
 *
 * <p>Deliberately a single snapshot rather than a method per number: the values are read together,
 * cached together, and read most often by a metrics scrape, where nine round trips to the database
 * per scrape would make monitoring the heaviest client the platform has.
 *
 * @param sitesByStatus                 site counts keyed by {@code SiteStatus} name
 * @param certificatesByStatus          certificate counts keyed by {@code CertificateStatus} name
 * @param pendingDeployments            deployments not yet in a terminal state
 * @param failedDeployments             deployments that ended in failure
 * @param outboxDepth                   messages waiting to be dispatched
 * @param outboxDead                    messages that exhausted their retries and need a person
 * @param instancesUnreachable          instances whose agent has not been heard from recently
 * @param instancesDrifted              instances serving a configuration the platform did not deploy
 * @param notificationsFailed           notifications that reached nobody
 * @param nearestCertificateExpiryDays  days until the soonest usable certificate expires,
 *                                      or -1 when none are installed
 */
public record PlatformStatistics(
        Map<String, Long> sitesByStatus,
        Map<String, Long> certificatesByStatus,
        long pendingDeployments,
        long failedDeployments,
        long outboxDepth,
        long outboxDead,
        long instancesUnreachable,
        long instancesDrifted,
        long notificationsFailed,
        long nearestCertificateExpiryDays) {

    public static PlatformStatistics empty() {
        return new PlatformStatistics(Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, -1);
    }

    public long sites(String status) {
        return sitesByStatus.getOrDefault(status, 0L);
    }

    public long certificates(String status) {
        return certificatesByStatus.getOrDefault(status, 0L);
    }
}
