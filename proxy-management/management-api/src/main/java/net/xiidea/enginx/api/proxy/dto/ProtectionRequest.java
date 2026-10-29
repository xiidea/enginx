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
        @Schema(description = "NONE, BASIC or EMAIL_OTP", example = "BASIC") String mode,
        @Valid @Size(max = 64) List<BasicAuthUserRequest> basicUsers,
        @Valid EmailOtpRequest emailOtp) {

    @Schema(name = "EmailOtpRequest", requiredProperties = {"allowedEmails"},
            description = "Who may sign in with a code sent by email. Required when the mode is EMAIL_OTP.")
    public record EmailOtpRequest(
            @Schema(description = "Exact addresses, or *@domain for everyone at a domain")
            @Size(max = 256) List<String> allowedEmails,
            @Schema(description = "How long a sign-in lasts, 300 to 2592000. Defaults to 86400.")
            Integer sessionTtlSeconds) {
    }

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
