package net.xiidea.enginx.domain.group;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DomainGroupRepository extends GroupHierarchy {

    DomainGroup save(DomainGroup group);

    Optional<DomainGroup> findById(UUID id);

    boolean existsByPath(GroupPath path);

    boolean hasChildren(UUID groupId);

    List<DomainGroup> findAll();

    void deleteById(UUID id);

    // ---- membership ----

    long countMembers(UUID groupId);

    Set<UUID> memberSiteIds(UUID groupId);

    boolean addMember(UUID groupId, UUID proxySiteId);

    boolean removeMember(UUID groupId, UUID proxySiteId);

    /** Groups a site belongs to, for display. */
    List<DomainGroup> findGroupsOfSite(UUID proxySiteId);
}
