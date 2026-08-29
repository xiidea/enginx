package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.notification.NotificationScanService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Looks for conditions worth telling someone about.
 *
 * <p>Hourly by default. The thresholds are measured in days, so a finer interval buys nothing, and
 * the ledger makes repeated runs harmless anyway.
 */
@DisallowConcurrentExecution
public class NotificationScanJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(NotificationScanJob.class);

    private final NotificationScanService scan;

    @Autowired
    public NotificationScanJob(NotificationScanService scan) {
        this.scan = scan;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            scan.scan();
        } catch (RuntimeException e) {
            // Never rethrow: a job that throws against a persistent store leaves its trigger in
            // ERROR and stops firing, so one bad scan would end notifications altogether — and
            // silently, which is the failure mode this whole phase exists to remove.
            log.error("Notification scan failed; the next run will retry", e);
        }
    }
}
