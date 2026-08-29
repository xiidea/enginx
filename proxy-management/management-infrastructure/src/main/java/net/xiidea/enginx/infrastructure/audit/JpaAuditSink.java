package net.xiidea.enginx.infrastructure.audit;

import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditSink;
import net.xiidea.enginx.infrastructure.persistence.entity.AuditLogEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AuditLogJpaRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Writes audit events into the append-only table.
 *
 * <p>This deliberately joins the caller's transaction rather than running in its own: an audit
 * row that survives a rolled-back change would be a record of something that never happened.
 */
@Component
public class JpaAuditSink implements AuditSink {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AuditLogJpaRepository repository;

    public JpaAuditSink(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(AuditEvent event) {
        repository.save(new AuditLogEntity(
                event.id(),
                event.occurredAt(),
                event.actorSubject(),
                event.actorUsername(),
                event.action(),
                event.resourceType(),
                event.resourceId(),
                toJson(event.beforeState()),
                toJson(event.afterState()),
                event.ipAddress(),
                truncate(event.userAgent(), 512),
                event.result(),
                truncate(event.errorMessage(), 2048),
                event.traceId()));
    }

    private static String toJson(Map<String, Object> state) {
        return state == null ? null : JSON.writeValueAsString(state);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
