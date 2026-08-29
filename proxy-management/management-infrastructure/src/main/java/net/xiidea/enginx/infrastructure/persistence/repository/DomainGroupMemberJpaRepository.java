package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.DomainGroupMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DomainGroupMemberJpaRepository extends JpaRepository<DomainGroupMemberEntity, UUID> {

    long countByDomainGroupId(UUID domainGroupId);

    List<DomainGroupMemberEntity> findByDomainGroupId(UUID domainGroupId);

    List<DomainGroupMemberEntity> findByProxySiteId(UUID proxySiteId);

    boolean existsByDomainGroupIdAndProxySiteId(UUID domainGroupId, UUID proxySiteId);

    @Query("delete from DomainGroupMemberEntity m where m.domainGroupId = :groupId and m.proxySiteId = :siteId")
    @org.springframework.data.jpa.repository.Modifying
    int deleteMembership(@Param("groupId") UUID groupId, @Param("siteId") UUID siteId);
}
