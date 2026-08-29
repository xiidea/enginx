package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.lifecycle.LifecycleSweepResult;
import net.xiidea.enginx.application.lifecycle.SiteLifecycleService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.PersistJobDataAfterExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Applies expiries and activations as their moment arrives.
 *
 * <p>{@link DisallowConcurrentExecution} keeps one node from overlapping itself if a sweep runs
 * long. Across nodes the clustered job store already guarantees a single fire per trigger, and
 * the claim query's {@code SKIP LOCKED} makes even a simultaneous run harmless — three
 * independent reasons the same site cannot be processed twice, because the consequence of getting
 * this wrong is a site expiring twice or, worse, not at all.
 */
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
public class LifecycleScanJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(LifecycleScanJob.class);

    private final SiteLifecycleService lifecycle;

    @Autowired
    public LifecycleScanJob(SiteLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            LifecycleSweepResult result = lifecycle.sweep();

            // A full batch means more sites are due than one pass took. Say so, rather than
            // leaving an operator to wonder why an expiry took several minutes to land.
            if (result.claimed() > 0) {
                log.debug("Lifecycle sweep claimed {} site(s)", result.claimed());
            }
        } catch (RuntimeException e) {
            // Never rethrow: an exception out of a Quartz job with a persistent store leaves the
            // trigger in ERROR and it stops firing altogether. A failed sweep must not stop every
            // future sweep, so it is logged and the next run tries again.
            log.error("Lifecycle sweep failed; the next run will retry", e);
        }
    }
}
