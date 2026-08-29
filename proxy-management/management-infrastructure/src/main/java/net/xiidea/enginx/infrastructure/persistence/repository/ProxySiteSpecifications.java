package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.permission.DomainPattern;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.infrastructure.persistence.entity.DomainGroupMemberEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteEntity;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates a {@link ProxySiteQuery} and an {@link AccessScope} into a JPA specification.
 *
 * <p>Both halves are applied in the query. Post-filtering an already-fetched page would produce
 * wrong page sizes and wrong totals, and the totals themselves would disclose how many sites
 * exist that the caller is not allowed to see.
 */
public final class ProxySiteSpecifications {

    private ProxySiteSpecifications() {
    }

    public static Specification<ProxySiteEntity> from(ProxySiteQuery query, AccessScope scope) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            Predicate permission = permissionPredicate(root, criteriaQuery, cb, scope);
            if (permission != null) {
                predicates.add(permission);
            }

            if (query.search() != null) {
                String pattern = "%" + escapeLike(query.search().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("domain")), pattern, '\\'),
                        cb.like(cb.lower(root.get("name")), pattern, '\\')));
            }
            if (!query.statuses().isEmpty()) {
                predicates.add(root.get("status").in(query.statuses()));
            }
            if (query.nginxInstanceId() != null) {
                predicates.add(cb.equal(root.get("nginxInstanceId"), query.nginxInstanceId()));
            }
            if (query.sslEnabled() != null) {
                predicates.add(cb.equal(root.get("sslEnabled"), query.sslEnabled()));
            }
            if (query.expiringBefore() != null) {
                predicates.add(cb.and(
                        cb.isNotNull(root.get("expiresAt")),
                        cb.lessThan(root.get("expiresAt"), query.expiringBefore())));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * @return null when every site is visible, so no predicate is added at all
     */
    private static Predicate permissionPredicate(Root<ProxySiteEntity> root,
                                                 jakarta.persistence.criteria.CriteriaQuery<?> criteriaQuery,
                                                 jakarta.persistence.criteria.CriteriaBuilder cb,
                                                 AccessScope scope) {
        if (scope.unrestricted()) {
            return null;
        }
        if (scope.deniesEverything()) {
            // An empty page, not an error: a caller with no grants has an empty list, and learns
            // nothing about what exists.
            return cb.disjunction();
        }

        List<Predicate> reachable = new ArrayList<>();

        if (!scope.siteIds().isEmpty()) {
            reachable.add(root.get("id").in(scope.siteIds()));
        }

        if (!scope.groupIds().isEmpty()) {
            // The group ids arrive already expanded to include descendants, so this stays a
            // single indexed `in` rather than a recursive walk.
            Subquery<java.util.UUID> membership = criteriaQuery.subquery(java.util.UUID.class);
            Root<DomainGroupMemberEntity> member = membership.from(DomainGroupMemberEntity.class);
            membership.select(member.get("proxySiteId"))
                    .where(member.get("domainGroupId").in(scope.groupIds()));
            reachable.add(root.get("id").in(membership));
        }

        for (DomainPattern pattern : scope.patterns()) {
            if (pattern.wildcard()) {
                // Reversed domains turn a suffix match into a prefix match, which the
                // text_pattern_ops index on domain_reversed can serve.
                reachable.add(cb.like(root.get("domainReversed"),
                        escapeLike(pattern.reversedPrefix()) + "%", '\\'));
            } else {
                reachable.add(cb.equal(root.get("domainReversed"), pattern.reversedPrefix()));
            }
        }

        return cb.or(reachable.toArray(new Predicate[0]));
    }

    /** Keeps a user-supplied {@code %} or {@code _} from being treated as a wildcard. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
