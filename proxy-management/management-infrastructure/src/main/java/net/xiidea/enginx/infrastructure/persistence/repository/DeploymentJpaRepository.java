package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.infrastructure.persistence.entity.DeploymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentJpaRepository extends JpaRepository<DeploymentEntity, UUID> {

    @Query("""
            select d from DeploymentEntity d
            where (:instanceId is null or d.nginxInstanceId = :instanceId)
              and (:#{#statuses == null || #statuses.isEmpty()} = true or d.status in :statuses)
            """)
    Page<DeploymentEntity> search(@Param("instanceId") UUID instanceId,
                                  @Param("statuses") Collection<DeploymentStatus> statuses,
                                  Pageable pageable);

    @Query("select d from DeploymentEntity d left join fetch d.events where d.id = :id")
    Optional<DeploymentEntity> findByIdWithEvents(@Param("id") UUID id);

    Optional<DeploymentEntity> findFirstByNginxInstanceIdOrderByCreatedAtDesc(UUID nginxInstanceId);

    boolean existsByNginxInstanceIdAndStatusIn(UUID nginxInstanceId, List<DeploymentStatus> statuses);
}
