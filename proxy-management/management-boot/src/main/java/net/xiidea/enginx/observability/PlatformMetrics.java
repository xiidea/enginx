package net.xiidea.enginx.observability;

import net.xiidea.enginx.domain.certificate.CertificateStatus;
import net.xiidea.enginx.domain.proxy.SiteStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * The gauges worth alerting on.
 *
 * <p>Chosen for what they would let someone catch before a user does: a site that expired without
 * anyone noticing, a deployment stuck in the outbox, a certificate approaching its last day, an
 * agent that has gone quiet. Meters that merely restate the size of a table are omitted — a metric
 * nobody would page on is noise on a dashboard and cost on every scrape.
 *
 * <p>Label sets are closed: one series per enum constant, fixed at startup. Tagging by site or
 * certificate id would grow the metric store without bound and is exactly how a cardinality
 * explosion starts.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformMetrics {

    public PlatformMetrics(MeterRegistry registry, PlatformStatisticsCache statistics) {
        for (SiteStatus status : SiteStatus.values()) {
            Gauge.builder("enginx.sites", () -> statistics.get().sites(status.name()))
                    .description("Proxy sites by lifecycle status")
                    .tag("status", status.name())
                    .register(registry);
        }

        for (CertificateStatus status : CertificateStatus.values()) {
            Gauge.builder("enginx.certificates", () -> statistics.get().certificates(status.name()))
                    .description("Certificates by status")
                    .tag("status", status.name())
                    .register(registry);
        }

        // The single most useful certificate metric: one threshold alert on this covers every
        // certificate the platform holds, present and future.
        Gauge.builder("enginx.certificates.nearest.expiry.days",
                        () -> statistics.get().nearestCertificateExpiryDays())
                .description("Days until the soonest certificate expiry; -1 when none are installed")
                .register(registry);

        // A number that stays above zero is the clearest sign an agent is unreachable.
        Gauge.builder("enginx.deployments.pending", () -> statistics.get().pendingDeployments())
                .description("Deployments queued or in progress")
                .register(registry);

        Gauge.builder("enginx.deployments.failed", () -> statistics.get().failedDeployments())
                .description("Deployments that ended in failure")
                .register(registry);

        Gauge.builder("enginx.outbox.depth", () -> statistics.get().outboxDepth())
                .description("Outbox messages waiting to be dispatched")
                .register(registry);

        Gauge.builder("enginx.outbox.dead", () -> statistics.get().outboxDead())
                .description("Outbox messages that exhausted their retries and need a person")
                .register(registry);

        Gauge.builder("enginx.instances.unreachable", () -> statistics.get().instancesUnreachable())
                .description("NGINX instances whose agent has not been heard from recently")
                .register(registry);

        // Serving something the platform did not deploy. Distinct from unreachable: these hosts
        // are answering, which is exactly why nothing else would notice.
        // Nothing retries a failed notification, and the ledger has already marked it handled, so
        // this number only ever goes up until someone looks.
        Gauge.builder("enginx.notifications.failed", () -> statistics.get().notificationsFailed())
                .description("Notifications that were claimed but reached no channel")
                .register(registry);

        Gauge.builder("enginx.instances.degraded", () -> statistics.get().instancesDrifted())
                .description("NGINX instances not serving what the platform deployed, or not serving at all")
                .register(registry);
    }
}
