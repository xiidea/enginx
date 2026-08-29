package net.xiidea.enginx.application.shared;

import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditSink;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes refusals in a transaction of their own.
 *
 * <p>A successful change and its audit row must commit or roll back together, so those share the
 * caller's transaction. A refusal is the opposite case: the operation is about to throw, and the
 * surrounding transaction is either doomed or read-only, so an audit row written inside it would
 * be discarded exactly when it matters most. Recording denials is a requirement, so it gets its
 * own transaction and survives the rollback that follows.
 *
 * <p>A separate bean rather than a method on {@link AuditRecorder}: a self-invocation would not
 * pass through the proxy, and the propagation would silently do nothing.
 */
@Component
public class AuditDenialWriter {

    private final AuditSink sink;

    public AuditDenialWriter(AuditSink sink) {
        this.sink = sink;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEvent event) {
        sink.record(event);
    }
}
