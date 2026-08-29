package net.xiidea.enginx.domain.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    OutboxMessage enqueue(OutboxMessage message);

    /**
     * Takes up to {@code limit} due messages for this worker.
     *
     * <p>Implemented with {@code SELECT … FOR UPDATE SKIP LOCKED}, so two replicas polling at the
     * same moment take disjoint sets and neither waits for the other. That is what makes the
     * dispatcher safe to run on every instance without any leader election.
     */
    List<OutboxMessage> claimBatch(int limit, Instant now);

    void markDone(UUID id);

    void markRetry(UUID id, int attempts, Instant nextAttemptAt, String error);

    void markDead(UUID id, String error);

    /** Messages that exhausted their retries. They will not be delivered without a person. */
    List<OutboxMessage> findDead(int limit);
}
