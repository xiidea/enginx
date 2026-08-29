package net.xiidea.enginx.api.audit;

import net.xiidea.enginx.api.common.PageResponse;
import net.xiidea.enginx.application.audit.AuditQueryService;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditQuery;
import net.xiidea.enginx.domain.audit.AuditResult;
import net.xiidea.enginx.domain.shared.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading the audit trail.
 *
 * <p>There is no write endpoint, and no delete. Audit rows are written by the operations they
 * describe, and the database refuses UPDATE and DELETE outright — so the absence here is not an
 * oversight to be filled in later.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Append-only record of security-sensitive and configuration-changing operations")
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Search the audit trail",
            description = "Requires global ADMIN: the trail spans every domain in the estate. Narrow the "
                    + "time range where possible — it lets PostgreSQL skip whole monthly partitions.")
    public PageResponse<AuditResponse> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) List<String> action,
            @RequestParam(required = false) List<String> result,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        AuditQuery query = new AuditQuery(
                actor,
                parse(AuditAction.class, action, "action"),
                parse(AuditResult.class, result, "result"),
                resourceType,
                resourceId,
                from,
                to,
                page,
                size);

        return PageResponse.from(audit.search(query), AuditController::toResponse);
    }

    @GetMapping("/actions")
    @Operation(summary = "The actions that can appear in the trail, for building a filter")
    public List<String> actions() {
        return Arrays.stream(AuditAction.values()).map(Enum::name).sorted().toList();
    }

    /**
     * @param beforeState state before the change, null when there was none
     * @param afterState  state after the change, null for a deletion or a denial
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AuditResponse",
            requiredProperties = {"id", "occurredAt", "action", "result"})
    public record AuditResponse(
            UUID id,
            Instant occurredAt,
            String actor,
            String actorSubject,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            String ipAddress,
            String result,
            String errorMessage) {
    }

    private static AuditResponse toResponse(AuditEvent event) {
        return new AuditResponse(
                event.id(),
                event.occurredAt(),
                event.actorUsername(),
                event.actorSubject(),
                event.action().name(),
                event.resourceType(),
                event.resourceId(),
                event.beforeState(),
                event.afterState(),
                event.ipAddress(),
                event.result().name(),
                event.errorMessage());
    }

    private static <E extends Enum<E>> Set<E> parse(Class<E> type, List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<E> parsed = new LinkedHashSet<>();
        for (String value : values) {
            try {
                parsed.add(Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException(field, "'" + value + "' is not valid. Expected one of: "
                        + String.join(", ", names(type)));
            }
        }
        return parsed;
    }

    private static <E extends Enum<E>> List<String> names(Class<E> type) {
        List<String> names = new ArrayList<>();
        for (E value : type.getEnumConstants()) {
            names.add(value.name());
        }
        return names;
    }
}
