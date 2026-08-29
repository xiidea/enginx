package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.permission.DomainPattern;
import net.xiidea.enginx.domain.permission.PermissionGrant;
import net.xiidea.enginx.domain.permission.PermissionGrantRepository;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.infrastructure.persistence.entity.PermissionGrantEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.PermissionGrantJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PermissionGrantRepositoryAdapter implements PermissionGrantRepository {

    private final PermissionGrantJpaRepository repository;

    public PermissionGrantRepositoryAdapter(PermissionGrantJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public PermissionGrant save(PermissionGrant grant) {
        PermissionGrantEntity entity = new PermissionGrantEntity(
                grant.id(),
                grant.subjectType(),
                grant.subjectRef(),
                grant.scopeType(),
                grant.scopeGroupId(),
                grant.scopeSiteId(),
                grant.domainPattern() == null ? null : grant.domainPattern().value(),
                grant.domainPattern() == null ? null : grant.domainPattern().reversedPrefix(),
                grant.level(),
                grant.grantedBy(),
                grant.grantedAt(),
                grant.expiresAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<PermissionGrant> findById(UUID id) {
        return repository.findById(id).map(PermissionGrantRepositoryAdapter::toDomain);
    }

    @Override
    public List<PermissionGrant> findActiveForSubjects(Collection<String> subjectRefs, Instant now) {
        if (subjectRefs == null || subjectRefs.isEmpty()) {
            return List.of();
        }
        return repository.findActiveForSubjects(subjectRefs, now).stream()
                .map(PermissionGrantRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<PermissionGrant> findBySubject(SubjectType subjectType, String subjectRef) {
        return repository.findBySubjectTypeAndSubjectRef(subjectType, subjectRef).stream()
                .map(PermissionGrantRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<PermissionGrant> findBySubjectAndScope(SubjectType subjectType, String subjectRef,
                                                           ScopeType scopeType, UUID scopeGroupId,
                                                           UUID scopeSiteId, String patternReversed) {
        return repository.findBySubjectAndScope(subjectType, subjectRef, scopeType,
                        scopeGroupId, scopeSiteId, patternReversed)
                .map(PermissionGrantRepositoryAdapter::toDomain);
    }

    @Override
    public List<PermissionGrant> findByScopeGroup(UUID groupId) {
        return repository.findByScopeGroupId(groupId).stream()
                .map(PermissionGrantRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<PermissionGrant> findByScopeSite(UUID siteId) {
        return repository.findByScopeSiteId(siteId).stream()
                .map(PermissionGrantRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public List<PermissionGrant> findAll() {
        return repository.findAll(Sort.by("subjectRef", "scopeType")).stream()
                .map(PermissionGrantRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private static PermissionGrant toDomain(PermissionGrantEntity entity) {
        DomainPattern pattern = entity.getPatternReversed() == null
                ? null
                : DomainPattern.rehydrate(entity.getDomainPattern(), entity.getPatternReversed());

        return new PermissionGrant(
                entity.getId(),
                entity.getSubjectType(),
                entity.getSubjectRef(),
                entity.getScopeType(),
                entity.getScopeGroupId(),
                entity.getScopeSiteId(),
                pattern,
                entity.getPermissionLevel(),
                entity.getGrantedBy(),
                entity.getGrantedAt(),
                entity.getExpiresAt());
    }
}
