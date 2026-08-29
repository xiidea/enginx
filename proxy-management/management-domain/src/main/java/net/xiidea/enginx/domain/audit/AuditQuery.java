package net.xiidea.enginx.domain.audit;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Filters for reading the audit trail.
 *
 * @param actor        a username or subject claim, matched exactly
 * @param resourceType free text rather than an enum, because the set grows with the system and a
 *                     stale enum would silently hide newer resource kinds from search
 */
public record AuditQuery(
        String actor,
        Set<AuditAction> actions,
        Set<AuditResult> results,
        String resourceType,
        UUID resourceId,
        Instant from,
        Instant to,
        int page,
        int size) {

    public static final int MAX_PAGE_SIZE = 200;

    public AuditQuery {
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        results = results == null ? Set.of() : Set.copyOf(results);
        if (page < 0) {
            throw new ValidationException("page", "Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size", "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from", "The start of the range must be before its end");
        }
        actor = blankToNull(actor);
        resourceType = blankToNull(resourceType);
    }

    public static AuditQuery firstPage() {
        return new AuditQuery(null, Set.of(), Set.of(), null, null, null, null, 0, 50);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Actions worth surfacing as a filter in a console, grouped as an operator thinks of them. */
    public static List<AuditAction> securityActions() {
        return List.of(AuditAction.ACCESS_DENIED, AuditAction.PERMISSION_GRANTED, AuditAction.PERMISSION_REVOKED);
    }
}
