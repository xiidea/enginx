package net.xiidea.enginx.domain.audit;

import net.xiidea.enginx.domain.shared.PageResult;

/**
 * Reads the audit trail.
 *
 * <p>Deliberately separate from {@link AuditSink}, which can only append. Keeping the two apart
 * means no code path that writes an audit event can accidentally gain the ability to read, edit or
 * remove one, and the append-only property stays visible in the type system rather than only in a
 * database trigger.
 */
public interface AuditLogReader {

    PageResult<AuditEvent> search(AuditQuery query);
}
