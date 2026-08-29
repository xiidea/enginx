package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.domain.outbox.OutboxMessage;
import net.xiidea.enginx.domain.outbox.OutboxRepository;
import net.xiidea.enginx.domain.outbox.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Drains the outbox.
 *
 * <p>Safe to run on every replica. The claim query uses {@code FOR UPDATE SKIP LOCKED}, so
 * simultaneous pollers take disjoint work without any leader election. Phase 5 replaces the
 * trigger with a clustered Quartz schedule; the claiming itself already tolerates a cluster and
 * will not need to change.
 */
@Component
public class OutboxDispatcherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherJob.class);
    private static final int BATCH_SIZE = 16;

    private final OutboxRepository outbox;
    private final DeploymentWorker worker;
    private final Clock clock;

    public OutboxDispatcherJob(OutboxRepository outbox, DeploymentWorker worker, Clock clock) {
        this.outbox = outbox;
        this.worker = worker;
        this.clock = clock;
    }

    /** @return how many messages were processed, for tests and metrics */
    public int drainOnce() {
        List<OutboxMessage> claimed = outbox.claimBatch(BATCH_SIZE, clock.instant());
        for (OutboxMessage message : claimed) {
            process(message);
        }
        return claimed.size();
    }

    private void process(OutboxMessage message) {
        try {
            boolean finished = worker.run(message);
            if (finished) {
                outbox.markDone(message.id());
                return;
            }
            retryOrGiveUp(message, "The deployment did not complete and will be retried");

        } catch (RuntimeException e) {
            // An unexpected failure here must not stop the rest of the batch, and must not lose
            // the message: it goes back on the queue with a backoff.
            log.error("Dispatching outbox message {} for {} {} failed",
                    message.id(), message.aggregateType(), message.aggregateId(), e);
            retryOrGiveUp(message, e.getMessage());
        }
    }

    private void retryOrGiveUp(OutboxMessage message, String error) {
        int attempts = message.attempts() + 1;
        if (RetryPolicy.exhausted(attempts)) {
            // Deliberately loud and terminal. Something that has failed five times over half an
            // hour needs a person, and continuing to retry would only bury the evidence.
            log.error("Giving up on {} {} after {} attempts: {}",
                    message.aggregateType(), message.aggregateId(), attempts, error);
            outbox.markDead(message.id(), error);
            return;
        }
        outbox.markRetry(message.id(), attempts, RetryPolicy.nextAttemptAt(attempts, clock.instant()), error);
    }
}
