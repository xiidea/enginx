package net.xiidea.enginx.api.permission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PermissionDtos {

    private PermissionDtos() {
    }

    /**
     * @param subjectRef for a USER grant, the Keycloak subject claim; for a GROUP grant, the
     *                   group path such as {@code /platform/production}. The console fills this
     *                   in from the identity listing so an operator never types a claim by hand.
     * @param expiresAt  optional, and the recommended way to hand out broad access. A grant that
     *                   lapses on its own cannot be forgotten.
     */
    public record GrantRequest(
            @NotNull(message = "Subject type is required") String subjectType,
            @NotBlank(message = "Subject reference is required") @Size(max = 512) String subjectRef,
            @NotNull(message = "Scope type is required") String scopeType,
            UUID scopeGroupId,
            UUID scopeSiteId,
            @Size(max = 255) String domainPattern,
            @NotNull(message = "Permission level is required") String level,
            Instant expiresAt) {
    }

    @Schema(name = "PermissionGrantResponse",
            requiredProperties = {"id", "subjectType", "subjectRef", "scopeType", "level", "grantedBy", "grantedAt", "expired"}, description = "One permission grant.")
    public record Response(
            UUID id,
            String subjectType,
            String subjectRef,
            String scopeType,
            UUID scopeGroupId,
            UUID scopeSiteId,
            String domainPattern,
            String level,
            String grantedBy,
            Instant grantedAt,
            Instant expiresAt,
            boolean expired) {
    }

    /** What the caller may do, so the console can hide actions the server would refuse anyway. */
    @Schema(name = "EffectivePermissionResponse", description = "What the caller may do to a site.", requiredProperties = {"proxySiteId", "domain", "canRead", "canOperate", "canManage", "canAdmin"})
    public record EffectivePermissionResponse(
            UUID proxySiteId,
            String domain,
            String level,
            boolean canRead,
            boolean canOperate,
            boolean canManage,
            boolean canAdmin) {
    }

    /** A candidate subject for a grant, drawn from the Keycloak mirror. */
    @Schema(name = "SubjectResponse", description = "A grantable subject.", requiredProperties = {"subjectType", "subjectRef", "displayName"})
    public record SubjectResponse(String subjectType, String subjectRef, String displayName) {
    }

    /**
     * @param totalMatched          how many sites the scope reaches, which may exceed the list
     * @param truncated             whether the list is partial
     * @param newlyVisibleToSubject how many of the listed sites the subject cannot already reach
     * @param coversFutureDomains   whether the scope will keep admitting domains created later
     */
    @Schema(name = "GrantPreviewResponse",
            requiredProperties = {"matched", "totalMatched", "truncated", "newlyVisibleToSubject", "coversFutureDomains"}, description = "What a grant would reach, before it is created.")
    public record PreviewResponse(
            List<MatchedSiteResponse> matched,
            long totalMatched,
            boolean truncated,
            long newlyVisibleToSubject,
            boolean coversFutureDomains) {
    }

    @Schema(name = "MatchedSiteResponse", description = "A site a grant would reach.", requiredProperties = {"id", "domain", "name", "alreadyReachable"})
    public record MatchedSiteResponse(UUID id, String domain, String name, boolean alreadyReachable) {
    }
}
