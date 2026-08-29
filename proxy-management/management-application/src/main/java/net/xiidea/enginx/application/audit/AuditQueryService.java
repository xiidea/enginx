package net.xiidea.enginx.application.audit;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditLogReader;
import net.xiidea.enginx.domain.audit.AuditQuery;
import net.xiidea.enginx.domain.shared.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the audit trail.
 *
 * <p>Gated on global ADMIN. The trail spans every domain in the estate and records who did what to
 * which host, so scoping it per-site would still leak the existence and shape of everything else
 * through the rows an operator could see. Narrower views — a site's own history — are better
 * served by the resource's own endpoints than by filtering this one.
 */
@Service
public class AuditQueryService {

    private final AuditLogReader reader;
    private final SitePermissionService permissions;

    public AuditQueryService(AuditLogReader reader, SitePermissionService permissions) {
        this.reader = reader;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public PageResult<AuditEvent> search(AuditQuery query) {
        permissions.requireGlobalAdmin();
        return reader.search(query);
    }
}
