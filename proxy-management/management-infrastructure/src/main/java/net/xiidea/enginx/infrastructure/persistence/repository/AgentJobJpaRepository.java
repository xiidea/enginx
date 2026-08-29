package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.domain.agent.AgentJobStatus;
import net.xiidea.enginx.infrastructure.persistence.entity.AgentJobEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentJobJpaRepository extends JpaRepository<AgentJobEntity, UUID> {

    /**
     * The oldest queued job for a host, but only while it holds none.
     *
     * <p>The {@code not exists} clause is the serialisation: one leased job per instance, so two
     * jobs never race to swap the same symlink. Expressed here as well as in the unique index
     * because reaching the index means an exception, and this means a quiet no.
     */
    @Query("""
            select j from AgentJobEntity j
            where j.nginxInstanceId = :instanceId
              and j.status = net.xiidea.enginx.domain.agent.AgentJobStatus.QUEUED
              and not exists (
                  select 1 from AgentJobEntity held
                  where held.nginxInstanceId = :instanceId
                    and held.status = net.xiidea.enginx.domain.agent.AgentJobStatus.LEASED
                    and held.leaseExpiresAt > :now)
            order by j.createdAt asc
            """)
    List<AgentJobEntity> findClaimable(@Param("instanceId") UUID instanceId,
                                       @Param("now") Instant now,
                                       Pageable limit);

    @Query("""
            select j from AgentJobEntity j
            where j.status = net.xiidea.enginx.domain.agent.AgentJobStatus.LEASED
              and j.leaseExpiresAt < :now
            order by j.leaseExpiresAt asc
            """)
    List<AgentJobEntity> findExpiredLeases(@Param("now") Instant now, Pageable limit);

    List<AgentJobEntity> findByDeploymentIdAndStatusIn(UUID deploymentId, List<AgentJobStatus> statuses);

    Optional<AgentJobEntity> findByIdAndNginxInstanceId(UUID id, UUID nginxInstanceId);
}
