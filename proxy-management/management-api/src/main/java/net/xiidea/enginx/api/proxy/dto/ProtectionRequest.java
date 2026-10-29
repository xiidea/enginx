package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(name = "ProtectionRequest", description = "Who may reach the site. Omit on update to keep the current protection.",
        requiredProperties = {"mode"})
public record ProtectionRequest(
        @NotBlank(message = "Protection mode is required")
        @Schema(description = "NONE or BASIC", example = "BASIC") String mode,
        @Valid @Size(max = 64) List<BasicAuthUserRequest> basicUsers) {

    @Schema(name = "BasicAuthUserRequest", requiredProperties = {"username"})
    public record BasicAuthUserRequest(
            @NotBlank(message = "Username is required") String username,
            @Schema(description = "The new password. Omit to keep the user's current one; required for a new user.",
                    format = "password", writeOnly = true)
            String password) {

        @Override
        public String toString() {
            return "BasicAuthUserRequest[username=" + username + ", password=" + (password == null ? "unchanged" : "<redacted>") + "]";
        }
    }
}
