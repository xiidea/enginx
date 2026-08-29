package net.xiidea.enginx.scheduler;

import net.xiidea.enginx.application.nginx.InstanceHeartbeatService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Asks every agent how it is.
 *
 * <p>This is what makes the agent-reachability health indicator mean anything: without it,
 * {@code last_seen_at} advances only when something is deployed, so a quiet estate is
 * indistinguishable from a dead one.
 */
@DisallowConcurrentExecution
public class InstanceHeartbeatJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeatJob.class);

    private final InstanceHeartbeatService heartbeat;

    @Autowired
    public InstanceHeartbeatJob(InstanceHeartbeatService heartbeat) {
        this.heartbeat = heartbeat;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            heartbeat.pollAll();
        } catch (RuntimeException e) {
            // Never rethrow: a job that throws against a persistent store leaves its trigger in
            // ERROR and stops firing, which would end monitoring rather than report a failure.
            log.error("Instance heartbeat failed; the next run will retry", e);
        }
    }
}
