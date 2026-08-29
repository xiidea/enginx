package net.xiidea.enginx.lifecycle;

import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestAgent;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the scheduler is genuinely database-backed and clustered.
 *
 * <p>This is the claim the expiration mechanism rests on: jobs must survive a restart, and two
 * replicas must not both process the same expiry. Asserting it against a real PostgreSQL is the
 * only way to know the Liquibase-managed {@code QRTZ_*} schema matches what Quartz expects — a
 * mismatch there fails at runtime, not at compile time.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
// This class runs with a live scheduler, so its context is closed when the class finishes.
// Left in the cache it would keep sweeping and dispatching against the shared database, and
// every later test would be racing a background job it never asked for.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "enginx.scheduler.enabled=true",
        "enginx.scheduler.lifecycle-interval-seconds=1",
        "enginx.scheduler.outbox-interval-seconds=1"
})
class QuartzSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Scheduler scheduler;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the scheduler runs against the database job store, in clustered mode")
    void schedulerIsClusteredAndPersistent() throws SchedulerException {
        assertThat(scheduler.isStarted()).isTrue();

        // A durable job store is what makes a pending expiry survive a restart; an in-memory
        // store would silently lose every trigger on shutdown.
        assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
        assertThat(scheduler.getMetaData().isJobStoreClustered()).isTrue();
    }

    @Test
    @DisplayName("every job and its trigger is stored in the database, not just in memory")
    void jobsArePersistedToTheJobStore() throws SchedulerException {
        List<String> expected = List.of(
                "site-lifecycle-scan",
                "outbox-dispatch",
                "certificate-monitor",
                // Creates next month's audit partitions before rows need them.
                "audit-maintenance",
                // Keeps last_seen_at moving, which is what lets the agent health indicator
                // distinguish a quiet estate from a dead one.
                "instance-heartbeat",
                // Pushes the conditions the health indicators already report to a person.
                "notification-scan");

        for (String name : expected) {
            JobKey key = JobKey.jobKey(name, "enginx");
            assertThat(scheduler.checkExists(key)).describedAs("job %s", name).isTrue();
            // Durable, so removing a trigger does not delete the job definition with it.
            assertThat(scheduler.getJobDetail(key).isDurable()).isTrue();
        }

        // Asserted as a set rather than a count, so adding a job does not silently pass by
        // matching a number while registering the wrong thing.
        List<String> storedJobs = jdbc.queryForList(
                "select job_name from qrtz_job_details where job_group = 'enginx'", String.class);
        List<String> storedTriggers = jdbc.queryForList(
                "select trigger_name from qrtz_triggers where trigger_group = 'enginx'", String.class);

        assertThat(storedJobs).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(storedTriggers).containsExactlyInAnyOrderElementsOf(
                expected.stream().map(name -> name + "-trigger").toList());
    }

    @Test
    @DisplayName("the cluster lock rows Quartz needs are present, which is what stops two nodes firing one job")
    void clusterLockRowsExist() {
        Integer locks = jdbc.queryForObject("select count(*) from qrtz_locks", Integer.class);
        assertThat(locks).isPositive();

        // Each running node registers itself; a node that dies is detected through this table and
        // its work recovered by another.
        Integer nodes = jdbc.queryForObject("select count(*) from qrtz_scheduler_state", Integer.class);
        assertThat(nodes).isPositive();
    }

    @Test
    @DisplayName("the lifecycle trigger actually fires on its schedule")
    void theLifecycleTriggerFires() {
        TriggerKey key = TriggerKey.triggerKey("site-lifecycle-scan-trigger", "enginx");

        Awaitility.await()
                .atMost(Duration.ofSeconds(40))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Trigger trigger = scheduler.getTrigger(key);
                    assertThat(trigger).isNotNull();
                    // The sweep starts ten seconds after boot so startup is not competing with it.
                    assertThat(trigger.getPreviousFireTime())
                            .describedAs("the lifecycle sweep should have run by now")
                            .isNotNull();
                });
    }
}
