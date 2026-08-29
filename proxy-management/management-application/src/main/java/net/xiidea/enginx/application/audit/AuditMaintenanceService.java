package net.xiidea.enginx.application.audit;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Keeps the audit table's partitions ahead of the clock, and applies retention behind it.
 *
 * <p>An insert into a partitioned table with no partition for its timestamp fails. The default
 * partition catches that, but a row landing there is a symptom rather than a design: it means the
 * maintenance job has not run, and every such row makes the eventual clean-up harder. Creating
 * partitions a couple of months ahead means a lapse of weeks is survivable.
 */
@Service
public class AuditMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AuditMaintenanceService.class);

    private final AuditPartitionManager partitions;
    private final AuditRecorder audit;
    private final Clock clock;
    private final int retentionMonths;

    /**
     * @param retentionMonths months of audit history to keep. Zero means keep everything, and is
     *                        the default deliberately: silently destroying an audit trail because
     *                        nobody set a property is a far worse failure than an unbounded table,
     *                        and it is the kind that is discovered during an investigation.
     */
    public AuditMaintenanceService(AuditPartitionManager partitions, AuditRecorder audit, Clock clock,
                                   @Value("${enginx.audit.retention-months:0}") int retentionMonths) {
        this.partitions = partitions;
        this.audit = audit;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
    }

    public int ensurePartitions() {
        return partitions.ensureAhead(2);
    }

    /**
     * Drops whole months that have fallen outside the retention window.
     *
     * <p>The deletion is itself audited. Removing part of the trail without recording that it
     * happened would leave a gap indistinguishable from one made by someone with database access,
     * which is the single thing an audit trail must never be ambiguous about.
     *
     * @return the partitions dropped
     */
    public List<String> applyRetention() {
        if (retentionMonths <= 0) {
            return List.of();
        }

        LocalDate cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .withDayOfMonth(1)
                .minusMonths(retentionMonths);

        List<String> dropped = partitions.dropPartitionsBefore(cutoff);
        if (dropped.isEmpty()) {
            return dropped;
        }

        log.warn("Audit retention dropped {} partition(s) ending on or before {}: {}",
                dropped.size(), cutoff, dropped);
        audit.success(AuditAction.AUDIT_RETENTION_APPLIED, "AUDIT_LOG", null, null,
                Map.of("cutoff", cutoff.toString(),
                        "droppedPartitions", String.join(",", dropped),
                        "retentionMonths", String.valueOf(retentionMonths)));
        return dropped;
    }
}
