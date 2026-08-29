package net.xiidea.enginx.application.shared;

import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditResult;
import net.xiidea.enginx.domain.audit.AuditSink;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Convenience over {@link AuditSink}: stamps the current actor, clock and request context so that
 * call sites only have to say what happened.
 */
@Component
public class AuditRecorder {

    private final AuditSink sink;
    private final AuditDenialWriter denialWriter;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public AuditRecorder(AuditSink sink, AuditDenialWriter denialWriter,
                         ActorProvider actorProvider, Clock clock) {
        this.sink = sink;
        this.denialWriter = denialWriter;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    public void success(AuditAction action, String resourceType, UUID resourceId,
                        Map<String, Object> before, Map<String, Object> after) {
        write(action, resourceType, resourceId, before, after, AuditResult.SUCCESS, null);
    }

    public void failure(AuditAction action, String resourceType, UUID resourceId, String errorMessage) {
        write(action, resourceType, resourceId, null, null, AuditResult.FAILURE, errorMessage);
    }

    /**
     * Records a refusal. Written in its own transaction, because the caller is about to throw and
     * an audit row inside the doomed transaction would be rolled back with it.
     */
    public void denied(AuditAction action, String resourceType, UUID resourceId, String errorMessage) {
        denialWriter.write(event(action, resourceType, resourceId, null, null,
                AuditResult.DENIED, errorMessage));
    }

    private void write(AuditAction action, String resourceType, UUID resourceId,
                       Map<String, Object> before, Map<String, Object> after,
                       AuditResult result, String errorMessage) {
        sink.record(event(action, resourceType, resourceId, before, after, result, errorMessage));
    }

    private AuditEvent event(AuditAction action, String resourceType, UUID resourceId,
                             Map<String, Object> before, Map<String, Object> after,
                             AuditResult result, String errorMessage) {
        Actor actor = actorProvider.currentActor();
        return new AuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                actor.subject(),
                actor.username(),
                action,
                resourceType,
                resourceId,
                before,
                after,
                actor.ipAddress(),
                actor.userAgent(),
                result,
                errorMessage,
                null);
    }
}
