package net.xiidea.enginx.application.group;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.group.DomainGroupRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain group use cases.
 *
 * <p>Groups exist so that a permission can be granted once over a set of sites that changes over
 * time. That makes membership itself a security-relevant operation, which is why adding a site to
 * a group is guarded on both the group and the site.
 */
@Service
public class DomainGroupService {

    private static final String RESOURCE_TYPE = "DOMAIN_GROUP";

    private final DomainGroupRepository groups;
    private final ProxySiteRepository sites;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public DomainGroupService(DomainGroupRepository groups,
                              ProxySiteRepository sites,
                              SitePermissionService permissions,
                              AuditRecorder audit,
                              ActorProvider actorProvider,
                              Clock clock) {
        this.groups = groups;
        this.sites = sites;
        this.permissions = permissions;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    /**
     * Lists the groups this caller can see. The tree is small and the filter is a cheap in-memory
     * pass, unlike the site listing where the row count makes query-level filtering essential.
     */
    @Transactional(readOnly = true)
    public List<DomainGroup> findVisible() {
        return groups.findAll().stream()
                .filter(group -> {
                    PermissionLevel level = permissions.effectiveGroupLevel(group);
                    return level != null && level.satisfies(PermissionLevel.READ);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public DomainGroup get(UUID id) {
        DomainGroup group = load(id);
        permissions.requireGroupPermission(group, PermissionLevel.READ);
        return group;
    }

    @Transactional
    public DomainGroup create(DomainGroupCommands.Create command) {
        DomainGroup group;
        if (command.parentId() == null) {
            permissions.requireRootGroupCreationPermission();
            group = DomainGroup.createRoot(UUID.randomUUID(), command.name(), command.slug(),
                    command.description(), actor(), clock.instant());
        } else {
            DomainGroup parent = load(command.parentId());
            permissions.requireGroupPermission(parent, PermissionLevel.MANAGE);
            group = DomainGroup.createChild(UUID.randomUUID(), parent, command.name(), command.slug(),
                    command.description(), actor(), clock.instant());
        }

        if (groups.existsByPath(group.path())) {
            throw new ConflictException("A group already exists at '" + group.path() + "'");
        }

        DomainGroup saved = groups.save(group);
        audit.success(AuditAction.DOMAIN_GROUP_CREATED, RESOURCE_TYPE, saved.id(), null, snapshot(saved));
        return saved;
    }

    @Transactional
    public DomainGroup rename(UUID id, String name, String description) {
        DomainGroup group = load(id);
        permissions.requireGroupPermission(group, PermissionLevel.MANAGE);

        Map<String, Object> before = snapshot(group);
        group.rename(name, description, clock.instant());
        DomainGroup saved = groups.save(group);

        audit.success(AuditAction.DOMAIN_GROUP_UPDATED, RESOURCE_TYPE, saved.id(), before, snapshot(saved));
        return saved;
    }

    /**
     * Deleting a group that still had members or children would silently revoke every grant made
     * over it, so both are refused rather than cascaded.
     */
    @Transactional
    public void delete(UUID id) {
        DomainGroup group = load(id);
        permissions.requireGroupPermission(group, PermissionLevel.ADMIN);

        group.requireNoChildren(groups.hasChildren(id));
        group.requireNoMembers(groups.countMembers(id));

        Map<String, Object> before = snapshot(group);
        groups.deleteById(id);
        audit.success(AuditAction.DOMAIN_GROUP_DELETED, RESOURCE_TYPE, id, before, null);
    }

    @Transactional
    public void addMember(UUID groupId, UUID siteId) {
        DomainGroup group = load(groupId);
        ProxySite site = loadSite(siteId);
        permissions.requireMembershipPermission(group, site);

        if (!groups.addMember(groupId, siteId)) {
            // Already a member. Idempotent rather than an error: the caller's intent is satisfied.
            return;
        }
        audit.success(AuditAction.DOMAIN_GROUP_MEMBER_ADDED, RESOURCE_TYPE, groupId, null,
                Map.of("proxySiteId", siteId.toString(), "domain", site.domain().value(),
                        "group", group.path().value()));
    }

    @Transactional
    public void removeMember(UUID groupId, UUID siteId) {
        DomainGroup group = load(groupId);
        ProxySite site = loadSite(siteId);
        permissions.requireMembershipPermission(group, site);

        if (!groups.removeMember(groupId, siteId)) {
            return;
        }
        audit.success(AuditAction.DOMAIN_GROUP_MEMBER_REMOVED, RESOURCE_TYPE, groupId,
                Map.of("proxySiteId", siteId.toString(), "domain", site.domain().value(),
                        "group", group.path().value()),
                null);
    }

    /**
     * The sites filed under a group.
     *
     * <p>Returns the sites themselves rather than their ids: every caller wants a domain to show,
     * and handing back bare ids only moves the lookup to whoever is least able to do it in one
     * query. Reading the group is the authorisation — a group grant reaches everything under it,
     * so being allowed to see the group is being allowed to see what is in it.
     */
    @Transactional(readOnly = true)
    public List<ProxySite> membersOf(UUID groupId) {
        DomainGroup group = load(groupId);
        permissions.requireGroupPermission(group, PermissionLevel.READ);
        return sites.findAllById(groups.memberSiteIds(groupId)).stream()
                .sorted(java.util.Comparator.comparing(site -> site.spec().domain().value()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DomainGroup> groupsOfSite(UUID siteId) {
        ProxySite site = loadSite(siteId);
        permissions.requireSiteAccess(site, PermissionLevel.READ);
        return groups.findGroupsOfSite(siteId);
    }

    private DomainGroup load(UUID id) {
        return groups.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }

    private ProxySite loadSite(UUID id) {
        return sites.findById(id).orElseThrow(() -> new NotFoundException("PROXY_SITE", id));
    }

    private static Map<String, Object> snapshot(DomainGroup group) {
        return Map.of(
                "id", group.id().toString(),
                "name", group.name(),
                "path", group.path().value(),
                "parentId", String.valueOf(group.parentId()));
    }

    private String actor() {
        return actorProvider.currentActor().username();
    }
}
