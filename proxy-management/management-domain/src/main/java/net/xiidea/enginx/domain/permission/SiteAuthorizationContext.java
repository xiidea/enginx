package net.xiidea.enginx.domain.permission;

import net.xiidea.enginx.domain.proxy.ProxySite;

import java.util.Set;
import java.util.UUID;

/**
 * Everything the evaluator needs to know about a site, and nothing else.
 *
 * @param groupIdsIncludingAncestors the site's own domain groups plus every ancestor of those
 *                                   groups, so that a grant on a parent reaches this site
 */
public record SiteAuthorizationContext(UUID siteId, String domainReversed, Set<UUID> groupIdsIncludingAncestors) {

    public SiteAuthorizationContext {
        groupIdsIncludingAncestors = groupIdsIncludingAncestors == null ? Set.of() : Set.copyOf(groupIdsIncludingAncestors);
    }

    public static SiteAuthorizationContext of(ProxySite site, Set<UUID> groupIdsIncludingAncestors) {
        return new SiteAuthorizationContext(site.id(), site.domain().reversed(), groupIdsIncludingAncestors);
    }

    /**
     * Context for a site that does not exist yet. It belongs to no group, so only global and
     * pattern grants can reach it. See {@code PermissionEvaluationService#canCreate}.
     */
    public static SiteAuthorizationContext prospective(String domainReversed) {
        return new SiteAuthorizationContext(null, domainReversed, Set.of());
    }
}
