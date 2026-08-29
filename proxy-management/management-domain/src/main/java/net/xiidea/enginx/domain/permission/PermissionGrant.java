package net.xiidea.enginx.domain.permission;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * One grant of a permission level to a subject over a scope.
 *
 * <p>There is no DENY. Effective access is the maximum over every applicable grant, which is
 * order-independent, cheap to compute as an aggregate, and decomposes into a SQL predicate so
 * list endpoints can filter in the query rather than after the fact. Exclusions are expressed by
 * granting more narrowly, not by a second rule that fights the first.
 */
public record PermissionGrant(
        UUID id,
        SubjectType subjectType,
        String subjectRef,
        ScopeType scopeType,
        UUID scopeGroupId,
        UUID scopeSiteId,
        DomainPattern domainPattern,
        PermissionLevel level,
        String grantedBy,
        Instant grantedAt,
        Instant expiresAt) {

    public PermissionGrant {
        if (subjectType == null || subjectRef == null || subjectRef.isBlank()) {
            throw new ValidationException("subject", "A grant needs a subject");
        }
        subjectRef = subjectRef.trim();
        if (scopeType == null) {
            throw new ValidationException("scopeType", "A grant needs a scope");
        }
        if (level == null) {
            throw new ValidationException("level", "A grant needs a permission level");
        }

        requireScopeReference(scopeType, scopeGroupId, scopeSiteId, domainPattern);
    }

    /**
     * Whether a scope type has been given the reference it needs, and nothing it must not have.
     *
     * <p>Exposed because the check has to happen before a caller acts on the reference, not only
     * when the grant is finally constructed. Authorising a grant means measuring what the grantor
     * holds over the scope, which means loading the site or group it names -- so a {@code SITE}
     * grant with no site id reaches a repository lookup on {@code null} and fails as a server
     * error, several steps before this constructor would have called it what it is.
     *
     * <p>Kept here rather than copied into the caller so there is one statement of the rule. A
     * second copy is a copy that can drift, and the one that drifts is the one nothing runs.
     */
    public static void requireScopeReference(ScopeType scopeType, UUID scopeGroupId, UUID scopeSiteId,
                                             DomainPattern domainPattern) {
        switch (scopeType) {
            case GLOBAL -> {
                if (scopeGroupId != null || scopeSiteId != null || domainPattern != null) {
                    throw new ValidationException("scope", "A global grant carries no scope reference");
                }
            }
            case DOMAIN_GROUP -> {
                if (scopeGroupId == null) {
                    throw new ValidationException("scopeGroupId", "A domain group grant needs a group");
                }
            }
            case SITE -> {
                if (scopeSiteId == null) {
                    throw new ValidationException("scopeSiteId", "A site grant needs a site");
                }
            }
            case DOMAIN_PATTERN -> {
                if (domainPattern == null) {
                    throw new ValidationException("domainPattern", "A pattern grant needs a pattern");
                }
            }
        }
    }

    public boolean isActiveAt(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }

    /**
     * Whether this grant reaches the given site.
     *
     * <p>{@code groupIdsIncludingAncestors} carries the site's own groups <em>and</em> every
     * ancestor of those groups, so a grant made on a parent group reaches sites filed under any
     * of its children without walking the tree here.
     */
    public boolean matches(SiteAuthorizationContext site) {
        return switch (scopeType) {
            case GLOBAL -> true;
            case SITE -> scopeSiteId.equals(site.siteId());
            case DOMAIN_GROUP -> site.groupIdsIncludingAncestors().contains(scopeGroupId);
            case DOMAIN_PATTERN -> domainPattern.matches(site.domainReversed());
        };
    }
}
