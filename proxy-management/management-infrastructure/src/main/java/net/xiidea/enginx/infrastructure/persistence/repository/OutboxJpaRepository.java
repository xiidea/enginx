package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.OutboxMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxMessageEntity, UUID> {

    /**
     * Claims due messages for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what lets every replica run the dispatcher without any
     * leader election: two workers polling simultaneously take disjoint rows and neither blocks
     * on the other. A plain {@code FOR UPDATE} would serialise them into a queue; no locking at
     * all would let both dispatch the same deployment.
     *
     * <p>Native SQL because JPQL has no way to express SKIP LOCKED.
     */
    @Query(value = """
            select * from outbox_messages
            where status in ('NEW', 'IN_PROGRESS')
              and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxMessageEntity> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = "update outbox_messages set status = 'IN_PROGRESS' where id in :ids", nativeQuery = true)
    int markInProgress(@Param("ids") List<UUID> ids);

    @Modifying
    @Query(value = "update outbox_messages set status = 'DONE', last_error = null where id = :id",
            nativeQuery = true)
    int markDone(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            update outbox_messages
            set status = 'NEW', attempts = :attempts, next_attempt_at = :nextAttemptAt, last_error = :error
            where id = :id
            """, nativeQuery = true)
    int markRetry(@Param("id") UUID id, @Param("attempts") int attempts,
                  @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error);

    @Modifying
    @Query(value = "update outbox_messages set status = 'DEAD', last_error = :error where id = :id",
            nativeQuery = true)
    int markDead(@Param("id") UUID id, @Param("error") String error);

    /** Messages that gave up. Ordered oldest first: the one that has been stuck longest matters most. */
    @Query("select m from OutboxMessageEntity m where m.status = 'DEAD' order by m.createdAt")
    List<OutboxMessageEntity> findDead(Pageable pageable);
}
