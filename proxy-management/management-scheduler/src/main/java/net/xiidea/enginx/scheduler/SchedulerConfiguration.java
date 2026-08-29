package net.xiidea.enginx.scheduler;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Durable jobs and their triggers.
 *
 * <p>The job store is the database, so these definitions are written to {@code QRTZ_JOB_DETAILS}
 * and {@code QRTZ_TRIGGERS} on first start and survive restarts. Declaring them as beans means
 * Spring re-registers them each boot, which also updates an interval that has been changed in
 * configuration.
 */
@Configuration
@ConditionalOnProperty(name = "enginx.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfiguration {

    static final String LIFECYCLE_JOB = "site-lifecycle-scan";
    static final String OUTBOX_JOB = "outbox-dispatch";
    static final String CERTIFICATE_JOB = "certificate-monitor";
    static final String AUDIT_JOB = "audit-maintenance";
    static final String HEARTBEAT_JOB = "instance-heartbeat";
    static final String NOTIFICATION_JOB = "notification-scan";
    static final String GROUP = "enginx";

    @Bean
    JobDetail lifecycleScanJobDetail() {
        return JobBuilder.newJob(LifecycleScanJob.class)
                .withIdentity(LIFECYCLE_JOB, GROUP)
                .withDescription("Applies expiries and activations whose moment has arrived")
                // Durable so the definition outlives its triggers; without this Quartz deletes the
                // job as soon as a trigger is removed, and the next boot has to recreate it.
                .storeDurably()
                .build();
    }

    @Bean
    Trigger lifecycleScanTrigger(@Value("${enginx.scheduler.lifecycle-interval-seconds:60}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(lifecycleScanJobDetail())
                .withIdentity(LIFECYCLE_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        // If the cluster was down over a scheduled fire, run once now and carry
                        // on. The alternative, replaying every missed fire, would be pointless:
                        // the sweep asks what is due now, so one run catches up on all of them.
                        .withMisfireHandlingInstructionFireNow())
                .startAt(java.util.Date.from(java.time.Instant.now().plus(Duration.ofSeconds(10))))
                .build();
    }

    @Bean
    JobDetail certificateMonitorJobDetail() {
        return JobBuilder.newJob(CertificateMonitorJob.class)
                .withIdentity(CERTIFICATE_JOB, GROUP)
                .withDescription("Refreshes certificate status and renews what is due")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger certificateMonitorTrigger(@Value("${enginx.scheduler.certificate-interval-seconds:3600}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(certificateMonitorJobDetail())
                .withIdentity(CERTIFICATE_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        // One catch-up run is enough: the sweep asks what is due now, so replaying
                        // every missed fire would repeat the same work.
                        .withMisfireHandlingInstructionFireNow())
                // Not at boot: startup is busy, and nothing expires in the first minute that did
                // not already expire before.
                .startAt(java.util.Date.from(java.time.Instant.now().plus(Duration.ofSeconds(30))))
                .build();
    }

    @Bean
    JobDetail auditMaintenanceJobDetail() {
        return JobBuilder.newJob(AuditMaintenanceJob.class)
                .withIdentity(AUDIT_JOB, GROUP)
                .withDescription("Creates audit partitions ahead of the clock")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger auditMaintenanceTrigger(@Value("${enginx.scheduler.audit-interval-seconds:86400}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(auditMaintenanceJobDetail())
                .withIdentity(AUDIT_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        .withMisfireHandlingInstructionFireNow())
                // Shortly after boot, so a deployment that has been down over a month boundary
                // creates the missing partition before the first audit row needs it.
                .startAt(java.util.Date.from(java.time.Instant.now().plus(Duration.ofSeconds(20))))
                .build();
    }

    @Bean
    JobDetail outboxDispatchJobDetail() {
        return JobBuilder.newJob(OutboxDispatchJob.class)
                .withIdentity(OUTBOX_JOB, GROUP)
                .withDescription("Sends queued deployments to their agents")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger outboxDispatchTrigger(@Value("${enginx.scheduler.outbox-interval-seconds:5}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(outboxDispatchJobDetail())
                .withIdentity(OUTBOX_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    @Bean
    JobDetail instanceHeartbeatJobDetail() {
        return JobBuilder.newJob(InstanceHeartbeatJob.class)
                .withIdentity(HEARTBEAT_JOB, GROUP)
                .withDescription("Records whether each agent is reachable and its host is serving")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger instanceHeartbeatTrigger(@Value("${enginx.scheduler.heartbeat-interval-seconds:60}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(instanceHeartbeatJobDetail())
                .withIdentity(HEARTBEAT_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        // Only the latest observation matters, so a backlog of missed fires is
                        // replaced by a single run rather than replayed.
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .startAt(java.util.Date.from(java.time.Instant.now().plus(Duration.ofSeconds(15))))
                .build();
    }

    @Bean
    JobDetail notificationScanJobDetail() {
        return JobBuilder.newJob(NotificationScanJob.class)
                .withIdentity(NOTIFICATION_JOB, GROUP)
                .withDescription("Reports expiries, failed renewals, unreachable hosts and abandoned work")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger notificationScanTrigger(@Value("${enginx.scheduler.notification-interval-seconds:3600}") int seconds) {
        return TriggerBuilder.newTrigger()
                .forJob(notificationScanJobDetail())
                .withIdentity(NOTIFICATION_JOB + "-trigger", GROUP)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(seconds)
                        .repeatForever()
                        // One catch-up run, not a replay: the scan asks what is true now, so
                        // repeating missed fires would find the same answer each time.
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                // Later than the other jobs. A cold start with an unreachable agent should not
                // send an outage notice before the first heartbeat has had a chance to succeed.
                .startAt(java.util.Date.from(java.time.Instant.now().plus(Duration.ofSeconds(120))))
                .build();
    }
}
