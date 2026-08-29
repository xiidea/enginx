package net.xiidea.enginx.domain.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable record of a security-sensitive or configuration-changing operation.
 *
 * <p>Before and after states are plain maps: serialising them to JSON is the persistence
 * adapter's job, not the domain's.
 */
public record AuditEvent(
        UUID id,
        Instant occurredAt,
        String actorSubject,
        String actorUsername,
        AuditAction action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        String ipAddress,
        String userAgent,
        AuditResult result,
        String errorMessage,
        String traceId) {

    public AuditEvent {
        beforeState = beforeState == null ? null : Map.copyOf(beforeState);
        afterState = afterState == null ? null : Map.copyOf(afterState);
    }
}
