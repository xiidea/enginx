package net.xiidea.enginx.api.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class DomainGroupDtos {

    private DomainGroupDtos() {
    }

    /**
     * @param slug the path segment this group occupies under its parent. The full path is derived
     *             from the parent rather than accepted from the client, so a caller cannot place
     *             a group somewhere they lack authority by crafting the path themselves.
     */
    @Schema(name = "CreateDomainGroupRequest", description = "A new domain group.")
    public record CreateRequest(
            @NotBlank(message = "Name is required") @Size(max = 128) String name,
            @NotBlank(message = "Slug is required")
            @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$",
                    message = "Slug must be lowercase letters, digits and hyphens")
            String slug,
            @Size(max = 512) String description,
            UUID parentId) {
    }

    @Schema(name = "UpdateDomainGroupRequest", description = "Changes to a domain group.")
    public record UpdateRequest(
            @NotBlank(message = "Name is required") @Size(max = 128) String name,
            @Size(max = 512) String description) {
    }

    /**
     * @param yourLevel the caller's own effective level on this group, so the console can hide
     *                  actions it would be refused. The server remains the authority: this is a
     *                  convenience for the UI, never the check itself.
     */
    @Schema(name = "DomainGroupResponse",
            requiredProperties = {"id", "name", "path", "depth", "memberCount", "createdBy", "createdAt", "version"}, description = "One domain group.")
    public record Response(
            UUID id,
            UUID parentId,
            String name,
            String path,
            int depth,
            String description,
            long memberCount,
            String yourLevel,
            String createdBy,
            Instant createdAt,
            long version) {
    }

    public record MemberResponse(UUID proxySiteId, String domain, String status) {
    }
}
