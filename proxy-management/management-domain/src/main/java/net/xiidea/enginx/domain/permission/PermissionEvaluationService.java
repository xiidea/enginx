package net.xiidea.enginx.domain.permission;

import net.xiidea.enginx.domain.group.GroupHierarchy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides what a caller may do. Pure: no Spring, no database, no clock of its own.
 *
 * <p>Effective access is the maximum over every grant that reaches the target, unioned with the
 * floor the caller's realm roles confer, then clamped by any ceiling those roles impose. Because
 * the rule is a maximum rather than a precedence chain, the answer does not depend on the order
 * grants were created or loaded in, which is what makes it safe to decompose into the SQL
 * predicate that {@link #accessibleScope} produces.
 */
public final class PermissionEvaluationService {

    /**
     * @param grants every active grant addressed to this caller; the caller's own subject and
     *               each of their group paths
     */
    public PermissionLevel effectiveLevel(AuthenticatedSubject subject,
                                          SiteAuthorizationContext site,
                                          Collection<PermissionGrant> grants,
                                          Instant now) {
        if (subject.isSuperAdmin()) {
            return PermissionLevel.ADMIN;
        }

        PermissionLevel level = subject.roleFloor();
        for (PermissionGrant grant : grants) {
            if (grant.isActiveAt(now) && grant.matches(site)) {
                level = PermissionLevel.highest(level, grant.level());
            }
        }
        return clamp(level, subject.roleCeiling());
    }

    public boolean hasPermission(AuthenticatedSubject subject,
                                 SiteAuthorizationContext site,
                                 PermissionLevel required,
                                 Collection<PermissionGrant> grants,
                                 Instant now) {
        PermissionLevel level = effectiveLevel(subject, site, grants, now);
        return level != null && level.satisfies(required);
    }

    /**
     * Whether the caller may create a site on a domain that does not exist yet.
     *
     * <p>A prospective site is in no domain group, so a DOMAIN_GROUP grant cannot authorise its
     * creation. This is deliberate rather than an oversight: if it could, holding MANAGE on any
     * group would let someone create <em>any</em> domain simply by filing it under that group,
     * turning a grant over a set of sites into a grant over the whole namespace. Authority over
     * a namespace comes from a global or pattern grant; authority over a group governs the sites
     * already in it.
     */
    public boolean canCreate(AuthenticatedSubject subject,
                             String domainReversed,
                             Collection<PermissionGrant> grants,
                             Instant now) {
        return hasPermission(subject, SiteAuthorizationContext.prospective(domainReversed),
                PermissionLevel.MANAGE, grants, now);
    }

    /**
     * The caller's level over a domain group itself, as opposed to the sites inside it.
     *
     * <p>Only global and domain-group grants can apply. A site or pattern grant says nothing
     * about the group that happens to contain that site, and authority flows down the tree only:
     * a grant on a child does not confer anything over its parent.
     *
     * @param groupIdsIncludingAncestors the group plus every ancestor of it
     */
    public PermissionLevel effectiveGroupLevel(AuthenticatedSubject subject,
                                               Set<UUID> groupIdsIncludingAncestors,
                                               Collection<PermissionGrant> grants,
                                               Instant now) {
        if (subject.isSuperAdmin()) {
            return PermissionLevel.ADMIN;
        }

        PermissionLevel level = subject.roleFloor();
        for (PermissionGrant grant : grants) {
            if (!grant.isActiveAt(now)) {
                continue;
            }
            boolean applies = switch (grant.scopeType()) {
                case GLOBAL -> true;
                case DOMAIN_GROUP -> groupIdsIncludingAncestors.contains(grant.scopeGroupId());
                case SITE, DOMAIN_PATTERN -> false;
            };
            if (applies) {
                level = PermissionLevel.highest(level, grant.level());
            }
        }
        return clamp(level, subject.roleCeiling());
    }

    /**
     * The caller's level over every site there is, from global grants and realm roles alone.
     */
    public PermissionLevel globalLevel(AuthenticatedSubject subject,
                                       Collection<PermissionGrant> grants,
                                       Instant now) {
        if (subject.isSuperAdmin()) {
            return PermissionLevel.ADMIN;
        }

        PermissionLevel level = subject.roleFloor();
        for (PermissionGrant grant : grants) {
            if (grant.isActiveAt(now) && grant.scopeType() == ScopeType.GLOBAL) {
                level = PermissionLevel.highest(level, grant.level());
            }
        }
        return clamp(level, subject.roleCeiling());
    }

    /**
     * The caller's level over an entire namespace, as opposed to over the sites currently in it.
     *
     * <p>Only a global grant or a pattern grant that <em>contains</em> the namespace counts. A
     * grant on one site inside the namespace says nothing about the namespace as a whole, and
     * treating it as if it did would let authority over {@code app.example.com} become authority
     * to hand out {@code *.example.com}.
     */
    public PermissionLevel namespaceLevel(AuthenticatedSubject subject,
                                          DomainPattern namespace,
                                          Collection<PermissionGrant> grants,
                                          Instant now) {
        if (subject.isSuperAdmin()) {
            return PermissionLevel.ADMIN;
        }

        PermissionLevel level = subject.roleFloor();
        for (PermissionGrant grant : grants) {
            if (!grant.isActiveAt(now)) {
                continue;
            }
            boolean applies = switch (grant.scopeType()) {
                case GLOBAL -> true;
                case DOMAIN_PATTERN -> namespace.isCoveredBy(grant.domainPattern());
                case DOMAIN_GROUP, SITE -> false;
            };
            if (applies) {
                level = PermissionLevel.highest(level, grant.level());
            }
        }
        return clamp(level, subject.roleCeiling());
    }

    /**
     * Reduces the caller's grants to a predicate a listing query can apply.
     *
     * @param atLeast the level a site must be reachable at to appear
     */
    public AccessScope accessibleScope(AuthenticatedSubject subject,
                                       Collection<PermissionGrant> grants,
                                       PermissionLevel atLeast,
                                       Instant now,
                                       GroupHierarchy hierarchy) {
        if (subject.isSuperAdmin()) {
            return AccessScope.all();
        }

        PermissionLevel ceiling = subject.roleCeiling();
        if (ceiling != null && !ceiling.satisfies(atLeast)) {
            // A READ_ONLY account can never reach OPERATE, no matter what it has been granted.
            return AccessScope.none();
        }

        PermissionLevel floor = subject.roleFloor();
        if (floor != null && floor.satisfies(atLeast)) {
            return AccessScope.all();
        }

        Set<UUID> siteIds = new LinkedHashSet<>();
        Set<UUID> grantedGroupIds = new LinkedHashSet<>();
        List<DomainPattern> patterns = new ArrayList<>();

        for (PermissionGrant grant : grants) {
            if (!grant.isActiveAt(now) || !grant.level().satisfies(atLeast)) {
                continue;
            }
            switch (grant.scopeType()) {
                case GLOBAL -> {
                    return AccessScope.all();
                }
                case SITE -> siteIds.add(grant.scopeSiteId());
                case DOMAIN_GROUP -> grantedGroupIds.add(grant.scopeGroupId());
                case DOMAIN_PATTERN -> patterns.add(grant.domainPattern());
            }
        }

        // A grant on a parent group reaches every site filed anywhere beneath it.
        Set<UUID> groupIds = grantedGroupIds.isEmpty() ? Set.of() : hierarchy.descendantIdsOf(grantedGroupIds);

        return AccessScope.restricted(siteIds, groupIds, patterns);
    }

    private static PermissionLevel clamp(PermissionLevel level, PermissionLevel ceiling) {
        if (level == null || ceiling == null) {
            return level;
        }
        return level.min(ceiling);
    }
}
