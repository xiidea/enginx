package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "OidcRoleDto", requiredProperties = {"role"},
        description = "A realm role when client is absent, otherwise a role on that client.")
public record OidcRoleDto(
        @Schema(description = "The client the role belongs to; absent for a realm role") String client,
        @NotBlank(message = "A role name is required") String role) {
}
