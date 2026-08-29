package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.infrastructure.persistence.entity.ConfigBundleEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConfigBundleJpaRepository extends JpaRepository<ConfigBundleEntity, UUID> {

    // Files are fetched with the bundle rather than lazily. A repository hands back a complete
    // aggregate: leaving the collection uninitialised makes the result depend on whether the
    // caller happens to hold a session, which is a trap that only appears outside a transaction.
    @Query("select b from ConfigBundleEntity b left join fetch b.files where b.id = :id")
    Optional<ConfigBundleEntity> findByIdWithFiles(@Param("id") UUID id);

    @Query("select b from ConfigBundleEntity b left join fetch b.files "
            + "where b.nginxInstanceId = :instanceId and b.contentHash = :contentHash")
    Optional<ConfigBundleEntity> findByInstanceAndHashWithFiles(@Param("instanceId") UUID instanceId,
                                                                @Param("contentHash") String contentHash);

    @Query("select b from ConfigBundleEntity b left join fetch b.files "
            + "where b.nginxInstanceId = :instanceId and b.renderStatus = :status")
    Optional<ConfigBundleEntity> findByInstanceAndStatusWithFiles(@Param("instanceId") UUID instanceId,
                                                                  @Param("status") BundleStatus status);

    List<ConfigBundleEntity> findByNginxInstanceIdOrderBySequenceDesc(UUID nginxInstanceId, Limit limit);

    @Query("select coalesce(max(b.sequence), 0) from ConfigBundleEntity b where b.nginxInstanceId = :instanceId")
    long maxSequence(@Param("instanceId") UUID instanceId);

    /**
     * Demotes whatever is currently active. Run immediately before promoting the new bundle, so
     * the partial unique index on ACTIVE is never violated mid-transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConfigBundleEntity b set b.renderStatus = 'SUPERSEDED' "
            + "where b.nginxInstanceId = :instanceId and b.renderStatus = 'ACTIVE' and b.id <> :keepId")
    int supersedeOthers(@Param("instanceId") UUID instanceId, @Param("keepId") UUID keepId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConfigBundleEntity b set b.renderStatus = :status where b.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") BundleStatus status);
}
