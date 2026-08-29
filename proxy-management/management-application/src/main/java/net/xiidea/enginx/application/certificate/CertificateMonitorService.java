package net.xiidea.enginx.application.certificate;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.CertificateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Keeps certificate status honest, and renews what is due.
 *
 * <p>Status is refreshed from each certificate's own dates rather than tracked by a timer, for the
 * same reason site expiry is swept rather than scheduled: a certificate whose renewal window
 * opened while the application was stopped is picked up by the next run, because the question is
 * about the present.
 *
 * <p>Renewal runs one certificate at a time and never in bulk. Authorities apply hard rate limits
 * counted per week, so a loop that retried everything on every pass is exactly how an account
 * loses the ability to issue anything at all.
 */
@Service
public class CertificateMonitorService {

    private static final Logger log = LoggerFactory.getLogger(CertificateMonitorService.class);
    private static final String RESOURCE_TYPE = "CERTIFICATE";

    /** Small on purpose: renewals are slow, rate-limited, and not urgent to the minute. */
    private static final int RENEWALS_PER_RUN = 5;
    private static final int STATUS_REFRESH_BATCH = 200;

    private final CertificateRepository certificates;
    private final CertificateService certificateService;
    private final AuditRecorder audit;
    private final Clock clock;

    public CertificateMonitorService(CertificateRepository certificates,
                                     CertificateService certificateService,
                                     AuditRecorder audit,
                                     Clock clock) {
        this.certificates = certificates;
        this.certificateService = certificateService;
        this.audit = audit;
        this.clock = clock;
    }

    /** @return how many certificates changed status or were renewed */
    public MonitorResult run() {
        int reclassified = refreshStatuses();
        int renewed = renewDue();
        return new MonitorResult(reclassified, renewed);
    }

    /**
     * Brings stored status in line with the certificates' dates.
     *
     * <p>Transactional and separate from renewal: an operator's view of what is expiring must stay
     * accurate even when the authority is unreachable and nothing can be renewed.
     */
    @Transactional
    public int refreshStatuses() {
        List<Certificate> stale = certificates.findWithStaleStatus(clock.instant(), STATUS_REFRESH_BATCH);
        int changed = 0;

        for (Certificate certificate : stale) {
            CertificateStatus before = certificate.status();
            if (!certificate.refreshStatus(clock.instant())) {
                continue;
            }
            certificates.save(certificate);
            changed++;

            if (certificate.status() == CertificateStatus.EXPIRED
                    || certificate.status() == CertificateStatus.EXPIRING_SOON) {
                // Audited rather than merely logged: a certificate quietly expiring is the kind of
                // thing that has to be explainable afterwards.
                audit.success(AuditAction.CERTIFICATE_EXPIRING, RESOURCE_TYPE, certificate.id(),
                        Map.of("status", before.name()),
                        Map.of("status", certificate.status().name(),
                                "daysRemaining", String.valueOf(certificate.daysRemaining(clock.instant())),
                                "domains", String.join(", ", certificate.domains())));
            }
            log.info("Certificate {} ({}) is now {}", certificate.id(), certificate.name(), certificate.status());
        }
        return changed;
    }

    /**
     * Renews the certificates that are due.
     *
     * <p>Not transactional: each renewal is a call to an external authority that can take tens of
     * seconds, and holding a transaction open across it would pin a database connection for the
     * duration. Each attempt records its own outcome.
     */
    public int renewDue() {
        Instant now = clock.instant();
        List<Certificate> due = certificates.findDueForRenewal(now, RENEWALS_PER_RUN);
        int renewed = 0;

        for (Certificate certificate : due) {
            try {
                log.info("Renewing certificate {} ({}), {} day(s) remaining",
                        certificate.id(), certificate.name(), certificate.daysRemaining(now));
                certificateService.renewAsSystem(certificate.id());
                renewed++;
            } catch (RuntimeException e) {
                // One failure must not stop the rest of the batch. The certificate records its own
                // error, and the next run will try it again — subject to the backoff the failure
                // itself imposes through the certificate's status.
                log.error("Renewal of certificate {} failed", certificate.id(), e);
            }
        }
        return renewed;
    }

    public record MonitorResult(int reclassified, int renewed) {

        public boolean changedAnything() {
            return reclassified > 0 || renewed > 0;
        }
    }
}
