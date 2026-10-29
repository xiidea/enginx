package net.xiidea.enginx.lifecycle;

import net.xiidea.enginx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node with the scheduler turned off runs no jobs at all.
 *
 * <p>Not merely none of its own: the job store is shared, so a scheduler started here would fire
 * every job the other nodes registered. In this suite those are the one-second triggers of
 * {@link QuartzSchedulerIntegrationTest}, which raced other tests' cleanup into deadlocks.
 */
class SchedulerDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Scheduler scheduler;

    @Test
    @DisplayName("with enginx.scheduler.enabled=false the scheduler is never started")
    void schedulerIsNotStarted() throws SchedulerException {
        assertThat(scheduler.isStarted()).isFalse();
    }
}
