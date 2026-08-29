package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.group.DomainGroupRepository;
import net.xiidea.enginx.domain.group.GroupPath;
import net.xiidea.enginx.infrastructure.persistence.entity.DomainGroupEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.DomainGroupMemberEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.DomainGroupJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.DomainGroupMemberJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DomainGroupRepositoryAdapter implements DomainGroupRepository {

    private final DomainGroupJpaRepository groups;
    private final DomainGroupMemberJpaRepository members;

    public DomainGroupRepositoryAdapter(DomainGroupJpaRepository groups, DomainGroupMemberJpaRepository members) {
        this.groups = groups;
        this.members = members;
    }

    @Override
    public DomainGroup save(DomainGroup group) {
        DomainGroupEntity entity = groups.findById(group.id()).orElseGet(() -> new DomainGroupEntity(group.id()));
        entity.setParentId(group.parentId());
        entity.setName(group.name());
        entity.setPath(group.path().value());
        entity.setDescription(group.description());
        entity.setCreatedBy(group.createdBy());
        entity.setCreatedAt(group.createdAt());
        entity.setUpdatedAt(group.updatedAt());
        return toDomain(groups.saveAndFlush(entity));
    }

    @Override
    public Optional<DomainGroup> findById(UUID id) {
        return groups.findById(id).map(DomainGroupRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByPath(GroupPath path) {
        return groups.existsByPath(path.value());
    }

    @Override
    public boolean hasChildren(UUID groupId) {
        return groups.existsByParentId(groupId);
    }

    @Override
    public List<DomainGroup> findAll() {
        return groups.findAll(Sort.by("path")).stream().map(DomainGroupRepositoryAdapter::toDomain).toList();
    }

    @Override
    public void deleteById(UUID id) {
        groups.deleteById(id);
    }

    // ---- hierarchy ----

    @Override
    public Set<UUID> descendantIdsOf(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Set.of();
        }
        return groups.findSelfAndDescendants(groupIds).stream()
                .map(DomainGroupEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Ancestors are derived from the materialised path rather than by walking parent links, so
     * resolving a site's whole ancestry costs one query no matter how deep the tree is.
     */
    @Override
    public Set<UUID> ancestorIdsOf(Collection<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Set.of();
        }
        List<DomainGroupEntity> own = groups.findByIdIn(groupIds);

        Set<String> paths = new LinkedHashSet<>();
        for (DomainGroupEntity entity : own) {
            paths.add(entity.getPath());
            paths.addAll(new GroupPath(entity.getPath()).ancestorPaths());
        }
        if (paths.isEmpty()) {
            return Set.of();
        }
        return groups.findByPathIn(paths).stream()
                .map(DomainGroupEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Set<UUID> groupIdsForSite(UUID siteId) {
        return members.findByProxySiteId(siteId).stream()
                .map(DomainGroupMemberEntity::getDomainGroupId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---- membership ----

    @Override
    public long countMembers(UUID groupId) {
        return members.countByDomainGroupId(groupId);
    }

    @Override
    public Set<UUID> memberSiteIds(UUID groupId) {
        return members.findByDomainGroupId(groupId).stream()
                .map(DomainGroupMemberEntity::getProxySiteId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @Transactional
    public boolean addMember(UUID groupId, UUID proxySiteId) {
        if (members.existsByDomainGroupIdAndProxySiteId(groupId, proxySiteId)) {
            return false;
        }
        members.save(new DomainGroupMemberEntity(UUID.randomUUID(), groupId, proxySiteId, "", Instant.now()));
        return true;
    }

    @Override
    @Transactional
    public boolean removeMember(UUID groupId, UUID proxySiteId) {
        return members.deleteMembership(groupId, proxySiteId) > 0;
    }

    @Override
    public List<DomainGroup> findGroupsOfSite(UUID proxySiteId) {
        Set<UUID> ids = groupIdsForSite(proxySiteId);
        return ids.isEmpty() ? List.of()
                : groups.findByIdIn(ids).stream().map(DomainGroupRepositoryAdapter::toDomain).toList();
    }

    private static DomainGroup toDomain(DomainGroupEntity entity) {
        return DomainGroup.rehydrate(entity.getId(), entity.getParentId(), entity.getName(),
                new GroupPath(entity.getPath()), entity.getDescription(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }
}
