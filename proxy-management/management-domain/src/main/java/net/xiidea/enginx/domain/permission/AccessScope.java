package net.xiidea.enginx.domain.permission;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The set of sites a caller may see, expressed as predicates rather than as a list of ids.
 *
 * <p>This exists so that a listing can be filtered <em>in the query</em>. Fetching a page and
 * then discarding rows would produce wrong page sizes and wrong totals, and the totals themselves
 * would leak the existence of sites the caller is not allowed to know about.
 *
 * @param unrestricted every site is visible; no predicate is needed
 * @param siteIds      sites reachable through a SITE grant
 * @param groupIds     domain groups reachable through a DOMAIN_GROUP grant, already expanded to
 *                     include descendants
 * @param patterns     domain patterns reachable through a DOMAIN_PATTERN grant
 */
public record AccessScope(boolean unrestricted, Set<UUID> siteIds, Set<UUID> groupIds, List<DomainPattern> patterns) {

    private static final AccessScope UNRESTRICTED = new AccessScope(true, Set.of(), Set.of(), List.of());
    private static final AccessScope NONE = new AccessScope(false, Set.of(), Set.of(), List.of());

    public AccessScope {
        siteIds = siteIds == null ? Set.of() : Set.copyOf(siteIds);
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /** Every site is visible; the listing query needs no permission predicate at all. */
    public static AccessScope all() {
        return UNRESTRICTED;
    }

    /** Nothing is visible. A listing returns an empty page, not an error. */
    public static AccessScope none() {
        return NONE;
    }

    public static AccessScope restricted(Set<UUID> siteIds, Set<UUID> groupIds, List<DomainPattern> patterns) {
        AccessScope scope = new AccessScope(false, siteIds, groupIds, patterns);
        return scope.deniesEverything() ? NONE : scope;
    }

    public boolean deniesEverything() {
        return !unrestricted && siteIds.isEmpty() && groupIds.isEmpty() && patterns.isEmpty();
    }
}
