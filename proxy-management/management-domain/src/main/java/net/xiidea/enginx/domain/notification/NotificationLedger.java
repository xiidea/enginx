package net.xiidea.enginx.domain.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers what has already been sent.
 *
 * <p>The claim is an insert protected by a unique constraint rather than a read followed by a
 * write. Two schedulers scanning at the same moment would both see "not sent yet" and both send;
 * letting the database arbitrate makes that impossible without any locking of our own.
 */
public interface NotificationLedger {

    /**
     * Takes ownership of sending this notification, if nobody has already.
     *
     * @return the ledger row id when this caller should send, or empty when it has been sent
     *         before — or is being sent right now by someone else
     */
    Optional<UUID> claim(NotificationEvent event, Instant now);

    /** Records what happened, so a channel that is failing is visible rather than merely quiet. */
    void recordOutcome(UUID ledgerId, boolean delivered, String recipients, String detail);

    /**
     * Records that nothing carried this notification because no channel is configured to.
     *
     * <p>Separate from {@link #recordOutcome} deliberately: a notification routed nowhere by
     * configuration is not a channel that broke, and a metric counting failures must not include
     * it — an operator who narrowed a channel on purpose would otherwise be paged about it.
     */
    void recordSuppressed(UUID ledgerId, String reason);

    /** How many notifications failed to deliver. Surfaced as a metric. */
    long countFailed();
}
