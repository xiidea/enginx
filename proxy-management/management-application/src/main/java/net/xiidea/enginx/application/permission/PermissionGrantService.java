package net.xiidea.enginx.application.permission;

import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.group.DomainGroupRepository;
import net.xiidea.enginx.domain.permission.DomainPattern;
import net.xiidea.enginx.domain.permission.PermissionGrant;
import net.xiidea.enginx.domain.permission.PermissionGrantRepository;
import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Granting and revoking permissions.
 *
 * <p>Two rules run through everything here. Administering a scope requires ADMIN <em>on that
 * scope</em>, not a global role, so authority to delegate is itself delegable. And nobody may
 * grant more than they hold: without that, ADMIN on a narrow scope would be a route to becoming
 * a super administrator in one call.
 */
@Service
public class PermissionGrantService {

    private static final String RESOURCE_TYPE = "PERMISSION_GRANT";

    /** Enough to convey scale without turning a preview into a full listing. */
    private static final int PREVIEW_LIMIT = 50;

    private final PermissionGrantRepository grants;
    private final DomainGroupRepository groups;
    private final ProxySiteRepository sites;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public PermissionGrantService(PermissionGrantRepository grants,
                                  DomainGroupRepository groups,
                                  ProxySiteRepository sites,
                                  SitePermissionService permissions,
                                  AuditRecorder audit,
                                  ActorProvider actorProvider,
                                  Clock clock) {
        this.grants = grants;
        this.groups = groups;
        this.sites = sites;
        this.permissions = permissions;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    @Transactional
    public PermissionGrant grant(PermissionCommands.Grant command) {
        DomainPattern pattern = patternOf(command);
        // Before the authorization check, because the check loads whatever the scope names and a
        // missing reference would reach the repository as a null id.
        PermissionGrant.requireScopeReference(command.scopeType(), command.scopeGroupId(),
                command.scopeSiteId(), pattern);

        PermissionLevel holderLevel = holderLevelOnScope(command.scopeType(), command.scopeGroupId(),
                command.scopeSiteId(), pattern);
        permissions.requireGrantPermission(command.level(), holderLevel);

        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new ValidationException("expiresAt", "A grant's expiry must be in the future");
        }

        // A subject holds at most one grant per scope, so re-granting changes the level rather
        // than adding a competing rule. Two grants over one scope would be indistinguishable in
        // effect from the higher of the two, leaving an audit trail that explains nothing.
        Optional<PermissionGrant> existing = grants.findBySubjectAndScope(
                command.subjectType(), command.subjectRef(), command.scopeType(),
                command.scopeGroupId(), command.scopeSiteId(),
                pattern == null ? null : pattern.reversedPrefix());

        Map<String, Object> before = existing.map(PermissionGrantService::snapshot).orElse(null);

        // Raising someone else's level is bounded by the grantor's own authority, so an existing
        // grant offers no way around the check above.
        PermissionGrant grant = new PermissionGrant(
                existing.map(PermissionGrant::id).orElseGet(UUID::randomUUID),
                command.subjectType(),
                command.subjectRef(),
                command.scopeType(),
                command.scopeGroupId(),
                command.scopeSiteId(),
                pattern,
                command.level(),
                actor(),
                existing.map(PermissionGrant::grantedAt).orElse(now),
                command.expiresAt());

        PermissionGrant saved = grants.save(grant);
        audit.success(AuditAction.PERMISSION_GRANTED, RESOURCE_TYPE, saved.id(), before, snapshot(saved));
        return saved;
    }

    /**
     * What this grant would actually reach, without creating it.
     *
     * <p>Authorised exactly like {@link #grant}: seeing the reach of a grant you are entitled to
     * make discloses nothing you could not disclose to yourself by making it. Refusing here for
     * the same reasons keeps one rule rather than two that can drift apart.
     */
    @Transactional(readOnly = true)
    public GrantPreview preview(PermissionCommands.Grant command) {
        DomainPattern pattern = patternOf(command);
        PermissionGrant.requireScopeReference(command.scopeType(), command.scopeGroupId(),
                command.scopeSiteId(), pattern);

        permissions.requireGrantPermission(command.level(), holderLevelOnScope(
                command.scopeType(), command.scopeGroupId(), command.scopeSiteId(), pattern));

        AccessScope proposed = scopeOf(command, pattern);
        // What the subject can already reach, so the preview can say what is genuinely new rather
        // than restating access they hold by some other route.
        AccessScope existing = permissions.scopeOfSubject(
                command.subjectType(), command.subjectRef(), command.level());

        PageResult<ProxySite> page = sites.search(
                new ProxySiteQuery(null, Set.of(), null, null, null, 0, PREVIEW_LIMIT, List.of()), proposed);

        List<GrantPreview.MatchedSite> matched = page.content().stream()
                .map(site -> new GrantPreview.MatchedSite(site.id(), site.spec().domain().value(),
                        site.spec().name(), reaches(existing, site)))
                .toList();

        long alreadyReachable = matched.stream().filter(GrantPreview.MatchedSite::alreadyReachable).count();

        return new GrantPreview(matched, page.totalElements(),
                page.totalElements() > matched.size(),
                // Counted over the page rather than the whole set: an exact figure would need a
                // second query per site, and the number exists to convey scale, not to be summed.
                matched.size() - alreadyReachable,
                coversFutureDomains(command.scopeType(), pattern));
    }

    /** The scope a grant confers, expressed the same way a listing predicate is. */
    private static AccessScope scopeOf(PermissionCommands.Grant command, DomainPattern pattern) {
        return switch (command.scopeType()) {
            case GLOBAL -> AccessScope.all();
            case SITE -> AccessScope.restricted(Set.of(command.scopeSiteId()), Set.of(), List.of());
            case DOMAIN_GROUP -> AccessScope.restricted(Set.of(), Set.of(command.scopeGroupId()), List.of());
            case DOMAIN_PATTERN -> AccessScope.restricted(Set.of(), Set.of(), List.of(pattern));
        };
    }

    /**
     * A scope that admits domains not yet registered. The caveat that makes the preview honest:
     * the list is what the grant reaches <em>today</em>, and these keep applying.
     */
    private static boolean coversFutureDomains(ScopeType scopeType, DomainPattern pattern) {
        return switch (scopeType) {
            case GLOBAL, DOMAIN_GROUP -> true;
            case DOMAIN_PATTERN -> pattern != null && pattern.wildcard();
            case SITE -> false;
        };
    }

    private boolean reaches(AccessScope scope, ProxySite site) {
        if (scope.unrestricted()) {
            return true;
        }
        if (scope.siteIds().contains(site.id())) {
            return true;
        }
        String reversed = site.spec().domain().reversed();
        return scope.patterns().stream().anyMatch(pattern -> pattern.matches(reversed));
    }

    @Transactional
    public void revoke(UUID id) {
        PermissionGrant grant = grants.findById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));

        // Revoking is an administrative act over the same scope the grant covers.
        permissions.requireGrantPermission(grant.level(), holderLevelOnScope(
                grant.scopeType(), grant.scopeGroupId(), grant.scopeSiteId(), grant.domainPattern()));

        Map<String, Object> before = snapshot(grant);
        grants.deleteById(id);
        audit.success(AuditAction.PERMISSION_REVOKED, RESOURCE_TYPE, id, before, null);
    }

    /** Grants over one scope, for the permissions screen. */
    @Transactional(readOnly = true)
    public List<PermissionGrant> findForGroup(UUID groupId) {
        DomainGroup group = groups.findById(groupId)
                .orElseThrow(() -> new NotFoundException("DOMAIN_GROUP", groupId));
        permissions.requireGroupPermission(group, PermissionLevel.ADMIN);
        return grants.findByScopeGroup(groupId);
    }

    @Transactional(readOnly = true)
    public List<PermissionGrant> findForSite(UUID siteId) {
        ProxySite site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId));
        permissions.requireSiteAccess(site, PermissionLevel.ADMIN);
        return grants.findByScopeSite(siteId);
    }

    /** Every grant in the system. Reserved for a caller with global ADMIN. */
    @Transactional(readOnly = true)
    public List<PermissionGrant> findAll() {
        permissions.requireGlobalAdmin();
        return grants.findAll();
    }

    /**
     * The level the caller holds over the scope a grant would cover. This is what both
     * "may you administer here" and "may you confer this much" are measured against.
     */
    private static DomainPattern patternOf(PermissionCommands.Grant command) {
        return command.domainPattern() == null ? null : DomainPattern.of(command.domainPattern());
    }

    private PermissionLevel holderLevelOnScope(ScopeType scopeType, UUID groupId, UUID siteId,
                                               DomainPattern pattern) {
        return switch (scopeType) {
            case GLOBAL -> permissions.globalLevel();
            case DOMAIN_GROUP -> permissions.effectiveGroupLevel(
                    groups.findById(groupId).orElseThrow(() -> new NotFoundException("DOMAIN_GROUP", groupId)));
            case SITE -> permissions.effectiveLevel(
                    sites.findById(siteId).orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId)));
            // A pattern covers a namespace that may contain sites the caller cannot see, so the
            // only honest measure is the authority they hold over the whole namespace.
            case DOMAIN_PATTERN -> permissions.levelOverNamespace(pattern);
        };
    }

    private static Map<String, Object> snapshot(PermissionGrant grant) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", grant.id().toString());
        map.put("subjectType", grant.subjectType().name());
        map.put("subjectRef", grant.subjectRef());
        map.put("scopeType", grant.scopeType().name());
        map.put("level", grant.level().name());
        map.put("scopeGroupId", String.valueOf(grant.scopeGroupId()));
        map.put("scopeSiteId", String.valueOf(grant.scopeSiteId()));
        map.put("domainPattern", grant.domainPattern() == null ? "null" : grant.domainPattern().value());
        map.put("expiresAt", String.valueOf(grant.expiresAt()));
        return Map.copyOf(map);
    }

    private String actor() {
        return actorProvider.currentActor().username();
    }
}
