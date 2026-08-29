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
