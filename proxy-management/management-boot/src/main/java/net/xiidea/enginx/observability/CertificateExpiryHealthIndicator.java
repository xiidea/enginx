package net.xiidea.enginx.observability;

import net.xiidea.enginx.application.observability.PlatformStatistics;
import net.xiidea.enginx.domain.certificate.CertificateStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * How close the estate is to serving an expired certificate.
 *
 * <p>Automatic renewal is running, so this is not the mechanism that keeps certificates fresh — it
 * is the check on that mechanism. Renewal failing silently looks exactly like renewal working until
 * the day it doesn't, and by then the browser error is the first anyone hears of it.
 */
@Component("certificates")
public class CertificateExpiryHealthIndicator implements HealthIndicator {

    private final PlatformStatisticsCache statistics;
    private final ObservabilityProperties properties;

    public CertificateExpiryHealthIndicator(PlatformStatisticsCache statistics,
                                            ObservabilityProperties properties) {
        this.statistics = statistics;
        this.properties = properties;
    }

    @Override
    public Health health() {
        PlatformStatistics current = statistics.get();
        long nearest = current.nearestCertificateExpiryDays();
        long expired = current.certificates(CertificateStatus.EXPIRED.name());
        long failed = current.certificates(CertificateStatus.ERROR.name());

        // -1 means nothing is installed, which is a normal state for a fresh deployment and for an
        // HTTP-only estate. Treating "no certificates" as an expiry emergency would make the
        // indicator cry wolf from the first boot.
        boolean imminent = nearest >= 0 && nearest <= properties.certificateWarnDays();
        boolean broken = expired > 0 || failed > 0;

        Health.Builder health = imminent || broken ? Health.status(PlatformStatus.WARNING) : Health.up();
        return health
                .withDetail("nearestExpiryDays", nearest)
                .withDetail("expired", expired)
                .withDetail("failed", failed)
                .withDetail("warnDays", properties.certificateWarnDays())
                .build();
    }
}
