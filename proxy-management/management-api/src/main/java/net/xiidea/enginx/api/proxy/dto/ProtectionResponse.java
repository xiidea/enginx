package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Who may reach a site. Usernames only: a password hash is never returned. */
@Schema(name = "ProtectionResponse", requiredProperties = {"mode", "basicUsers"})
public record ProtectionResponse(String mode, List<BasicAuthUserResponse> basicUsers, EmailOtpResponse emailOtp,
                                 OidcResponse oidc, BasicLoginPageDto basicLoginPage) {

    /**
     * Present only when the mode is OIDC.
     *
     * @param redirectUri where the identity provider must be allowed to send visitors back to
     */
    @Schema(name = "OidcAccessResponse", requiredProperties = {"anyUser", "users", "roles", "sessionTtlSeconds", "redirectUri"})
    public record OidcResponse(boolean anyUser, List<String> users, List<OidcRoleDto> roles, long sessionTtlSeconds,
                               String redirectUri) {
    }

    /** Present only when the mode is EMAIL_OTP. */
    @Schema(name = "EmailOtpResponse", requiredProperties = {"allowedEmails", "sessionTtlSeconds"})
    public record EmailOtpResponse(List<String> allowedEmails, long sessionTtlSeconds) {
    }

    @Schema(name = "BasicAuthUserResponse", requiredProperties = {"username"})
    public record BasicAuthUserResponse(String username) {
    }
}
