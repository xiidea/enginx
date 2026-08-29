package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.CertificateStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationKind;
import net.xiidea.enginx.domain.notification.SiteNotificationSettings;
import net.xiidea.enginx.domain.notification.SiteNotificationSettingsRepository;
import net.xiidea.enginx.domain.outbox.OutboxMessage;
import net.xiidea.enginx.domain.outbox.OutboxRepository;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.proxy.SiteStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Looks for conditions worth telling someone about, and hands each to {@link NotificationService}.
 *
 * <p>A scan rather than an event stream. Every condition here is a function of state and time —
 * "expires within seven days" becomes true without anything happening — so there is no event to
 * subscribe to. The scan asks the same questions the health indicators ask, and differs only in
 * pushing the answer somewhere a person will see it without looking.
 *
 * <p>Sending is deduplicated by the ledger, so this may run as often as you like and will not
 * repeat itself.
 */
@Service
public class NotificationScanService {

    private static final Logger log = LoggerFactory.getLogger(NotificationScanService.class);

    /** Enough to notice a systemic problem; a cap so one bad day cannot generate thousands. */
    private static final int SCAN_LIMIT = 200;

    private final ProxySiteRepository sites;
    private final CertificateRepository certificates;
    private final NginxInstanceRepository instances;
    private final OutboxRepository outbox;
    private final ConfigBundleRepository bundles;
    private final NotificationService notifications;
    private final NotificationProperties properties;
    private final SiteNotificationSettingsRepository siteSettings;
    private final Clock clock;

    public NotificationScanService(ProxySiteRepository sites, CertificateRepository certificates,
                                   NginxInstanceRepository instances, OutboxRepository outbox,
                                   ConfigBundleRepository bundles, NotificationService notifications,
                                   NotificationProperties properties,
                                   SiteNotificationSettingsRepository siteSettings, Clock clock) {
        this.sites = sites;
        this.certificates = certificates;
        this.instances = instances;
        this.outbox = outbox;
        this.bundles = bundles;
        this.notifications = notifications;
        this.properties = properties;
        this.siteSettings = siteSettings;
        this.clock = clock;
    }

    /**
     * @return how many notifications were sent
     */
    @Transactional(readOnly = true)
    public int scan() {
        if (!properties.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        int sent = 0;

        sent += scanSites(now);
        sent += scanCertificates(now);
        sent += scanInstances();
        sent += scanOutbox();

        if (sent > 0) {
            log.info("Notification scan sent {} notification(s)", sent);
        }
        return sent;
    }

    // ---- sites -------------------------------------------------------------

    private int scanSites(Instant now) {
        Instant cutoff = now.plus(Duration.ofDays(properties.widestThresholdDays()));
        int sent = 0;

        List<ProxySite> approaching = sites.findWithExpiryBefore(cutoff, SCAN_LIMIT);
        // One query for the batch. Asking per site would turn one indexed read into a hundred, on
        // a sweep that already touched every one of them.
        Map<UUID, SiteNotificationSettings> settings = siteSettings.findForSites(
                approaching.stream().map(ProxySite::id).collect(java.util.stream.Collectors.toSet()));

        for (ProxySite site : approaching) {
            Instant expiry = site.spec().window().expiresAt();
            if (expiry == null) {
                continue;
            }

            SiteNotificationSettings notify = settings.getOrDefault(site.id(),
                    SiteNotificationSettings.defaultsFor(site.id()));
            if (!notify.expiryEnabled()) {
                // Opted out. Skipped before the ledger is touched, so re-enabling later still
                // sends: a claim recorded now would suppress the notification for good.
                continue;
            }
            // The fingerprint is the expiry instant. Extending a site's window changes it, which
            // re-arms every threshold for the new date without anything having to clear a flag.
            String fingerprint = String.valueOf(expiry.toEpochMilli());

            if (site.status() == SiteStatus.EXPIRED || !expiry.isAfter(now)) {
                sent += send(new NotificationEvent(
                        NotificationKind.SITE_EXPIRED, "PROXY_SITE", site.id(), "", fingerprint,
                        site.spec().domain().value() + " has expired and is no longer served",
                        """
                        The proxy site %s expired at %s and has been taken off the air. Requests \
                        for it now receive a 404 from the catch-all server.

                        To restore it, set a new expiry date or remove the expiry entirely.\
                        """.formatted(site.spec().domain().value(), expiry),
                        site.createdBy(), notify.subscribers()));
                continue;
            }

            long daysLeft = daysUntil(now, expiry);
            // The smallest threshold this site has fallen inside. Only one notification per scan
            // per site: crossing 7 and 3 between two runs should not send two messages, and the
            // more urgent of the two is the one worth having.
            Integer threshold = properties.expiryThresholds().stream()
                    .filter(days -> daysLeft <= days)
                    .min(Integer::compareTo)
                    .orElse(null);
            if (threshold == null) {
                continue;
            }

            long stated = daysRemainingForHumans(now, expiry);
            sent += send(new NotificationEvent(
                    NotificationKind.SITE_EXPIRING, "PROXY_SITE", site.id(),
                    String.valueOf(threshold), fingerprint,
                    site.spec().domain().value() + " expires " + describeRemaining(stated),
                    """
                    The proxy site %s is scheduled to expire at %s — %s. When it does, it will stop \
                    being served and requests will receive a 404.

                    If it is still needed, extend or remove its expiry before then.\
                    """.formatted(site.spec().domain().value(), expiry, describeRemaining(stated)),
                    site.createdBy(), notify.subscribers()));
        }
        return sent;
    }

    // ---- certificates ------------------------------------------------------

    private int scanCertificates(Instant now) {
        int sent = 0;

        for (Certificate certificate : certificates.findAll()) {
            if (certificate.status() == CertificateStatus.REVOKED) {
                continue;
            }

            if (certificate.status() == CertificateStatus.ERROR) {
                // Fingerprinted on the current expiry, so one report per certificate lifetime
                // rather than one per failed attempt. The expiry thresholds below escalate on
                // their own if nobody acts, which is the reminder that matters.
                sent += send(new NotificationEvent(
                        NotificationKind.CERTIFICATE_RENEWAL_FAILED, "CERTIFICATE", certificate.id(),
                        "", fingerprintOf(certificate),
                        "Certificate '" + certificate.name() + "' could not be issued or renewed",
                        """
                        Issuance or renewal failed for '%s' (%s).

                        Reported cause: %s

                        The previously installed certificate, if any, is still being served. \
                        Automatic renewal will keep trying; if the cause is a rate limit, stop and \
                        wait rather than retrying.\
                        """.formatted(certificate.name(), String.join(", ", certificate.domains()),
                                certificate.lastError() == null ? "none recorded" : certificate.lastError()),
                        certificate.createdBy()));
                continue;
            }

            if (certificate.metadata() == null || certificate.metadata().notAfter() == null) {
                continue;
            }
            long daysLeft = daysUntil(now, certificate.metadata().notAfter());
            Integer threshold = properties.expiryThresholds().stream()
                    .filter(days -> daysLeft <= days)
                    .min(Integer::compareTo)
                    .orElse(null);
            if (threshold == null) {
                continue;
            }

            long stated = daysRemainingForHumans(now, certificate.metadata().notAfter());
            sent += send(new NotificationEvent(
                    NotificationKind.CERTIFICATE_EXPIRING, "CERTIFICATE", certificate.id(),
                    String.valueOf(threshold), fingerprintOf(certificate),
                    "Certificate '" + certificate.name() + "' expires " + describeRemaining(stated),
                    """
                    The certificate '%s' covering %s expires at %s — %s.

                    Automatic renewal is %s. If it is enabled and this message still arrived, \
                    renewal is not completing — check the certificate's recent errors.\
                    """.formatted(certificate.name(), String.join(", ", certificate.domains()),
                            certificate.metadata().notAfter(), describeRemaining(stated),
                            certificate.autoRenew() ? "enabled" : "disabled"),
                    certificate.createdBy()));
        }
        return sent;
    }

    /**
     * Days remaining, rounded up, for choosing which threshold has been crossed.
     *
     * <p>Rounded up because a threshold is a claim: "within 3 days" must not be asserted for
     * something 3 days and 1 hour away. Rounding down would file it under the 3-day warning and
     * then say so in the subject line, which is the kind of small inaccuracy that teaches people
     * not to trust the rest of the message.
     */
    private static long daysUntil(Instant now, Instant when) {
        long minutes = ChronoUnit.MINUTES.between(now, when);
        if (minutes <= 0) {
            return 0;
        }
        return (minutes + 1439) / 1440;
    }

    /**
     * How long is left, phrased the way someone would say it.
     *
     * <p>"in 0 day(s)" is what a naive count produces for something five hours away, and it reads
     * as a bug rather than as urgency — which is the opposite of what the most urgent notification
     * in the set should do.
     */
    private static String describeRemaining(long days) {
        return switch ((int) Math.min(days, 2)) {
            case 0 -> "today";
            case 1 -> "in 1 day";
            default -> "in " + days + " days";
        };
    }

    /**
     * Days remaining as a person would say them, for the message itself.
     *
     * <p>Rounded down, which is the opposite of the bucketing above and deliberately so. Something
     * 2 days and 1 hour away belongs in the 3-day bucket — it is within three days — but telling
     * the reader it expires "in 3 days" is simply untrue. The bucket is a rule; this is a fact.
     */
    private static long daysRemainingForHumans(Instant now, Instant when) {
        return Math.max(0, ChronoUnit.DAYS.between(now, when));
    }

    /** A renewal produces a new expiry, which re-arms every threshold for the new certificate. */
    private static String fingerprintOf(Certificate certificate) {
        return certificate.metadata() == null || certificate.metadata().notAfter() == null
                ? "no-expiry"
                : String.valueOf(certificate.metadata().notAfter().toEpochMilli());
    }

    // ---- instances ---------------------------------------------------------

    private int scanInstances() {
        int sent = 0;

        for (NginxInstance instance : instances.findAll()) {
            if (instance.status() == InstanceStatus.OFFLINE) {
                // last_seen_at is deliberately frozen while an instance is unreachable, so it
                // identifies this outage. An instance that recovers and later fails again has a
                // newer value, and is therefore a new episode worth reporting.
                sent += send(new NotificationEvent(
                        NotificationKind.INSTANCE_OFFLINE, "NGINX_INSTANCE", instance.id(), "",
                        instance.lastSeenAt() == null ? "never-seen"
                                : String.valueOf(instance.lastSeenAt().toEpochMilli()),
                        "NGINX host '" + instance.name() + "' is not responding",
                        """
                        The agent on %s (%s) has not been heard from. Last contact: %s.

                        The host keeps serving whatever configuration it already has — this is a \
                        loss of control, not of traffic. Until it returns, no change can be \
                        deployed to it.\
                        """.formatted(instance.name(), instance.hostname(),
                                instance.lastSeenAt() == null ? "never" : instance.lastSeenAt()),
                        null));

            } else if (instance.status() == InstanceStatus.DEGRADED) {
                sent += send(new NotificationEvent(
                        NotificationKind.INSTANCE_DRIFTED, "NGINX_INSTANCE", instance.id(), "",
                        driftFingerprint(instance),
                        "NGINX host '" + instance.name() + "' is not serving what was deployed",
                        """
                        %s (%s) is answering, but what it is serving does not match what this \
                        platform deployed — or NGINX there is not running.

                        Nothing has been changed automatically: unexpected drift is often a person \
                        working on an incident. Check what happened before redeploying.\
                        """.formatted(instance.name(), instance.hostname()),
                        null));
            }
        }
        return sent;
    }

    // ---- outbox ------------------------------------------------------------

    private int scanOutbox() {
        int sent = 0;

        for (OutboxMessage message : outbox.findDead(SCAN_LIMIT)) {
            sent += send(new NotificationEvent(
                    NotificationKind.OUTBOX_MESSAGE_DEAD, "OUTBOX_MESSAGE", message.id(), "",
                    message.id().toString(),
                    "A change was accepted but will never reach its host",
                    """
                    An outbox message for %s %s exhausted its retries and has been abandoned.

                    Last error: %s

                    The API returned success for this change when it was requested, so nothing else \
                    will report it as failed. It needs a person.\
                    """.formatted(message.aggregateType(), message.aggregateId(),
                            message.lastError() == null ? "none recorded" : message.lastError()),
                    null));
        }
        return sent;
    }

    /**
     * Identifies one drift episode.
     *
     * <p>Not {@code lastSeenAt}: a degraded host is still answering, so that moves on every
     * heartbeat and the notification would repeat every minute. The bundle the platform expects the
     * host to be serving is stable for as long as the drift lasts — it changes only on a successful
     * deployment, which is also what ends the episode. A host that drifts again later does so from
     * a different expected bundle, and is reported again.
     */
    private String driftFingerprint(NginxInstance instance) {
        return bundles.findActiveForInstance(instance.id())
                .map(bundle -> bundle.id().toString())
                .orElse("no-bundle");
    }

    private int send(NotificationEvent event) {
        return notifications.notifyOnce(event) ? 1 : 0;
    }
}
