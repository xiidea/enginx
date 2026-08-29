package net.xiidea.enginx.domain.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Work committed alongside the state change that caused it.
 *
 * <p>A deployment is a database write plus a remote call. Doing both in one transaction is a
 * distributed-transaction bug either way round: commit then fail to call, or call then fail to
 * commit. Writing this row in the same local transaction as the deployment, and dispatching it
 * afterwards, makes the intent durable first and the side effect retryable (AD-5).
 */
public record OutboxMessage(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String messageType,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt) {

    public static final String DEPLOYMENT = "DEPLOYMENT";
    public static final String DISPATCH_DEPLOYMENT = "DISPATCH_DEPLOYMENT";

    public static OutboxMessage forDeployment(UUID deploymentId, Instant now) {
        return new OutboxMessage(UUID.randomUUID(), DEPLOYMENT, deploymentId, DISPATCH_DEPLOYMENT,
                OutboxStatus.NEW, 0, now, null, now);
    }
}
