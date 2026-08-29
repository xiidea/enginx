package net.xiidea.enginx.application.permission;

import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;

import java.time.Instant;
import java.util.UUID;

public final class PermissionCommands {

    private PermissionCommands() {
    }

    /**
     * @param subjectRef the Keycloak {@code sub} claim for a USER grant, or the group path for a
     *                   GROUP grant. Deliberately not a mirrored row id: authorization resolves
     *                   this against the access token, never against a table that could be stale.
     * @param expiresAt  optional. A time-boxed grant is the recommended way to hand out broad
     *                   access, and the permissions screen surfaces the ones about to lapse.
     */
    public record Grant(
            SubjectType subjectType,
            String subjectRef,
            ScopeType scopeType,
            UUID scopeGroupId,
            UUID scopeSiteId,
            String domainPattern,
            PermissionLevel level,
            Instant expiresAt) {
    }
}
