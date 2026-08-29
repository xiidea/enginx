package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.NotificationLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface NotificationLedgerJpaRepository extends JpaRepository<NotificationLedgerEntity, UUID> {

    /**
     * Claims a notification, or reports that someone already has.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching a constraint violation. Letting the
     * insert throw would work exactly once: the failed statement marks its transaction
     * rollback-only, so catching the exception and returning normally makes the *commit* fail
     * instead — with an UnexpectedRollbackException that surfaces at the caller, well away from
     * anything that explains it. Asking PostgreSQL to arbitrate silently keeps the whole thing to
     * one statement with no exception control flow.
     *
     * @return 1 when this caller claimed it, 0 when it was already claimed
     */
    @Modifying
    @Query(value = """
            insert into notification_ledger
                   (id, kind, resource_type, resource_id, threshold, fingerprint, status, created_at)
            values (:id, :kind, :resourceType, :resourceId, :threshold, :fingerprint, 'PENDING', :now)
            on conflict (kind, resource_id, threshold, fingerprint) do nothing
            """, nativeQuery = true)
    int claim(@Param("id") UUID id,
              @Param("kind") String kind,
              @Param("resourceType") String resourceType,
              @Param("resourceId") UUID resourceId,
              @Param("threshold") String threshold,
              @Param("fingerprint") String fingerprint,
              @Param("now") Instant now);

    @Query("select count(l) from NotificationLedgerEntity l where l.status = 'FAILED'")
    long countFailed();
}
