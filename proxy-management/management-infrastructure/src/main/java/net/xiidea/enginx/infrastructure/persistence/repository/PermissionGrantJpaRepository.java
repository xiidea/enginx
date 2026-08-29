package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.infrastructure.persistence.entity.PermissionGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionGrantJpaRepository extends JpaRepository<PermissionGrantEntity, UUID> {

    /**
     * The authorization hot path. One indexed query per request loads every grant addressed to
     * the caller's subject or to any group in their token, so evaluation itself touches no I/O.
     */
    @Query("""
            select g from PermissionGrantEntity g
            where g.subjectRef in :refs
              and (g.expiresAt is null or g.expiresAt > :now)
            """)
    List<PermissionGrantEntity> findActiveForSubjects(@Param("refs") Collection<String> refs,
                                                      @Param("now") Instant now);

    List<PermissionGrantEntity> findBySubjectTypeAndSubjectRef(SubjectType subjectType, String subjectRef);

    @Query("""
            select g from PermissionGrantEntity g
            where g.subjectType = :subjectType
              and g.subjectRef = :subjectRef
              and g.scopeType = :scopeType
              and (:groupId is null or g.scopeGroupId = :groupId)
              and (:siteId is null or g.scopeSiteId = :siteId)
              and (:pattern is null or g.patternReversed = :pattern)
            """)
    Optional<PermissionGrantEntity> findBySubjectAndScope(@Param("subjectType") SubjectType subjectType,
                                                          @Param("subjectRef") String subjectRef,
                                                          @Param("scopeType") ScopeType scopeType,
                                                          @Param("groupId") UUID groupId,
                                                          @Param("siteId") UUID siteId,
                                                          @Param("pattern") String pattern);

    List<PermissionGrantEntity> findByScopeGroupId(UUID scopeGroupId);

    List<PermissionGrantEntity> findByScopeSiteId(UUID scopeSiteId);

    /** Distinct subject references named by any grant, restricted to the ones asked about. */
    @Query("select distinct g.subjectRef from PermissionGrantEntity g where g.subjectRef in :refs")
    List<String> findDistinctSubjectRefsIn(@Param("refs") Collection<String> refs);
}
