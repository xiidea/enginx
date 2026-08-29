package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.audit.AuditMaintenanceService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Creates next month's audit partitions before they are needed.
 *
 * <p>Daily, because the work is idempotent and almost always a no-op; running it often is how a
 * missed day stops mattering.
 *
 * <p>Also applies audit retention, which is disabled unless {@code enginx.audit.retention-months}
 * is set. Keeping both in one job means the partitions that retention drops and the partitions
 * that maintenance creates can never be governed by two schedules that disagree.
 */
@DisallowConcurrentExecution
public class AuditMaintenanceJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AuditMaintenanceJob.class);

    private final AuditMaintenanceService maintenance;

    @Autowired
    public AuditMaintenanceJob(AuditMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            maintenance.ensurePartitions();
            // Retention runs after, and only when configured. Creating the next month's partition
            // must not be skipped because a drop failed — that is how a table stops accepting
            // inserts a month later, for a reason nobody connects to this job.
            maintenance.applyRetention();
        } catch (RuntimeException e) {
            // Never rethrow: a job that throws against a persistent store leaves its trigger in
            // ERROR and stops firing, so one bad day would end partition maintenance for good.
            log.error("Audit partition maintenance failed; the next run will retry", e);
        }
    }
}
