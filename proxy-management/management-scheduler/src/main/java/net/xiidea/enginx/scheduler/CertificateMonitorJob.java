package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.certificate.CertificateMonitorService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tracks certificate expiry and renews what is due.
 *
 * <p>Hourly rather than by the minute. Renewal windows are measured in days, and an authority's
 * rate limits punish eagerness far more than a certificate cares about an hour's delay.
 */
@DisallowConcurrentExecution
public class CertificateMonitorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(CertificateMonitorJob.class);

    private final CertificateMonitorService monitor;

    @Autowired
    public CertificateMonitorJob(CertificateMonitorService monitor) {
        this.monitor = monitor;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            CertificateMonitorService.MonitorResult result = monitor.run();
            if (result.changedAnything()) {
                log.info("Certificate monitor: {} reclassified, {} renewed",
                        result.reclassified(), result.renewed());
            }
        } catch (RuntimeException e) {
            // Never rethrow: an exception out of a Quartz job with a persistent store leaves the
            // trigger in ERROR and it stops firing, so one bad hour would end all monitoring.
            log.error("Certificate monitoring failed; the next run will retry", e);
        }
    }
}
