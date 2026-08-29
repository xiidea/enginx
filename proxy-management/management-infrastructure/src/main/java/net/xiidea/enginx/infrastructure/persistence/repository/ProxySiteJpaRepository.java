package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProxySiteJpaRepository
        extends JpaRepository<ProxySiteEntity, UUID>, JpaSpecificationExecutor<ProxySiteEntity> {

    @Query("""
            select count(s) > 0 from ProxySiteEntity s
            where s.nginxInstanceId = :instanceId
              and s.domain = :domain
              and (:excludeId is null or s.id <> :excludeId)
            """)
    boolean existsByInstanceAndDomain(@Param("instanceId") UUID instanceId,
                                      @Param("domain") String domain,
                                      @Param("excludeId") UUID excludeId);

    @Query("""
            select s from ProxySiteEntity s
            where s.nginxInstanceId = :instanceId
              and s.adminState = net.xiidea.enginx.domain.proxy.AdminState.ENABLED
              and (s.activeFrom is null or s.activeFrom <= :now)
              and (s.expiresAt  is null or s.expiresAt  > :now)
            order by s.domain
            """)
    List<ProxySiteEntity> findDeployable(@Param("instanceId") UUID instanceId, @Param("now") Instant now);

    /**
     * The lifecycle sweep's claim query.
     *
     * <p>Native SQL because JPQL cannot express SKIP LOCKED. The predicates deliberately mirror
     * the two partial indexes on this table, so the scan stays proportional to the number of
     * sites actually due rather than to the size of the table.
     */
    @Query(value = "select * from proxy_sites "
            + "where (status = 'ACTIVE' and expires_at is not null and expires_at <= :now) "
            + "   or (status = 'PENDING' and (active_from is null or active_from <= :now)) "
            + "order by coalesce(expires_at, active_from) "
            + "limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<ProxySiteEntity> claimDueForLifecycle(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Sites approaching or past their expiry, for the notification scan.
     *
     * <p>Ordered by expiry so the most urgent are reported first if the limit truncates. Disabled
     * sites are excluded: an operator who has already turned a site off does not need to be told
     * its window is closing.
     */
    @Query("""
            select s from ProxySiteEntity s
             where s.expiresAt is not null
               and s.expiresAt <= :cutoff
               and s.status in ('ACTIVE','EXPIRED')
             order by s.expiresAt
            """)
    List<ProxySiteEntity> findWithExpiryBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
