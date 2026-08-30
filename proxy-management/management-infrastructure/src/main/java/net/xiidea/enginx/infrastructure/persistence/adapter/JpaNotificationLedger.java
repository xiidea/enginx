package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationLedger;
import net.xiidea.enginx.infrastructure.persistence.repository.NotificationLedgerJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger, backed by a unique constraint.
 *
 * <p>Claiming is an insert that either succeeds or violates {@code uq_notification_once}. Doing it
 * as a read-then-write would leave a window in which two scheduler nodes both decide nothing has
 * been sent yet, and the recipient gets the message twice — rarely, and therefore in a way nobody
 * would reproduce.
 */
@Repository
public class JpaNotificationLedger implements NotificationLedger {

    private static final Logger log = LoggerFactory.getLogger(JpaNotificationLedger.class);

    private final NotificationLedgerJpaRepository repository;

    public JpaNotificationLedger(NotificationLedgerJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * REQUIRES_NEW so the claim commits independently of the scan that asked for it. The scan runs
     * read-only over a lot of state; the record that a notification is being sent must survive even
     * if something later in that scan goes wrong.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> claim(NotificationEvent event, Instant now) {
        UUID id = UUID.randomUUID();

        int inserted = repository.claim(id, event.kind().name(), event.resourceType(),
                event.resourceId(), event.threshold(), event.fingerprint(), now);

        if (inserted == 0) {
            log.debug("Notification {} for {} was already sent", event.kind(), event.resourceId());
            return Optional.empty();
        }
        return Optional.of(id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(UUID ledgerId, boolean delivered, String recipients, String detail) {
        repository.findById(ledgerId).ifPresent(entity -> {
            entity.recordOutcome(delivered ? "SENT" : "FAILED", truncate(recipients, 1024), detail);
            repository.save(entity);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressed(UUID ledgerId, String reason) {
        repository.findById(ledgerId).ifPresent(entity -> {
            entity.recordOutcome("SUPPRESSED", "", reason);
            repository.save(entity);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public long countFailed() {
        return repository.countFailed();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
