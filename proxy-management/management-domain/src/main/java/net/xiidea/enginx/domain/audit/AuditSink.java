package net.xiidea.enginx.domain.audit;

/**
 * Write-only port for the audit trail. There is deliberately no read or delete method:
 * the audit log is append-only from the application's point of view, and the database
 * enforces the same rule with a trigger.
 */
public interface AuditSink {

    void record(AuditEvent event);
}
