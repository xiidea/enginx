package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.SortDirection;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Filtering, sorting and pagination for a proxy site listing. */
public record ProxySiteQuery(
        String search,
        Set<SiteStatus> statuses,
        UUID nginxInstanceId,
        Boolean sslEnabled,
        Instant expiringBefore,
        int page,
        int size,
        List<Sort> sorts) {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 25;

    public record Sort(ProxySiteSortField field, SortDirection direction) {
        public Sort {
            if (field == null) {
                throw new ValidationException("sort", "Sort field is required");
            }
            direction = direction == null ? SortDirection.ASC : direction;
        }
    }

    public ProxySiteQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        sorts = (sorts == null || sorts.isEmpty())
                ? List.of(new Sort(ProxySiteSortField.CREATED_AT, SortDirection.DESC))
                : List.copyOf(sorts);
        if (page < 0) {
            throw new ValidationException("page", "Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size", "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (search != null) {
            search = search.trim();
            if (search.isEmpty()) {
                search = null;
            }
        }
    }

    public static ProxySiteQuery firstPage() {
        return new ProxySiteQuery(null, Set.of(), null, null, null, 0, DEFAULT_PAGE_SIZE, List.of());
    }
}
