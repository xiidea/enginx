package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.outbox.OutboxMessage;
import net.xiidea.enginx.domain.outbox.OutboxRepository;
import net.xiidea.enginx.domain.outbox.OutboxStatus;
import net.xiidea.enginx.infrastructure.persistence.entity.OutboxMessageEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.OutboxJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepositoryAdapter implements OutboxRepository {

    private final OutboxJpaRepository repository;

    public OutboxRepositoryAdapter(OutboxJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxMessage enqueue(OutboxMessage message) {
        // Joins the caller's transaction on purpose: the message and the state change it describes
        // must become durable together, or neither.
        OutboxMessageEntity saved = repository.save(new OutboxMessageEntity(
                message.id(), message.aggregateType(), message.aggregateId(), message.messageType(),
                message.status(), message.attempts(), message.nextAttemptAt(),
                message.lastError(), message.createdAt()));
        return toDomain(saved);
    }

    @Override
    @Transactional
    public List<OutboxMessage> claimBatch(int limit, Instant now) {
        List<OutboxMessageEntity> claimed = repository.claimDue(now, limit);
        if (claimed.isEmpty()) {
            return List.of();
        }
        // The row locks are held until this transaction commits, so marking them IN_PROGRESS here
        // is what stops another worker taking them once the locks are released.
        repository.markInProgress(claimed.stream().map(OutboxMessageEntity::getId).toList());
        return claimed.stream().map(OutboxRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(UUID id) {
        repository.markDone(id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        repository.markRetry(id, attempts, nextAttemptAt, truncate(error));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDead(UUID id, String error) {
        repository.markDead(id, truncate(error));
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= 4000) {
            return error;
        }
        return error.substring(0, 4000) + "… (truncated)";
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxMessage> findDead(int limit) {
        return repository.findDead(org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(OutboxRepositoryAdapter::toDomain).toList();
    }

    private static OutboxMessage toDomain(OutboxMessageEntity entity) {
        return new OutboxMessage(entity.getId(), entity.getAggregateType(), entity.getAggregateId(),
                entity.getMessageType(), entity.getStatus(), entity.getAttempts(),
                entity.getNextAttemptAt(), entity.getLastError(), entity.getCreatedAt());
    }
}
