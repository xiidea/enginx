package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.deployment.OutboxDispatcherJob;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Drains the deployment outbox.
 *
 * <p>Scheduled through Quartz so the trigger survives a restart along with everything else, but
 * the work itself does not depend on that: claiming uses {@code FOR UPDATE SKIP LOCKED}, so it
 * would be correct even if every node polled at once.
 */
@DisallowConcurrentExecution
public class OutboxDispatchJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchJob.class);

    private final OutboxDispatcherJob dispatcher;

    @Autowired
    public OutboxDispatchJob(OutboxDispatcherJob dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            dispatcher.drainOnce();
        } catch (RuntimeException e) {
            log.error("Outbox dispatch failed; the next run will retry", e);
        }
    }
}
