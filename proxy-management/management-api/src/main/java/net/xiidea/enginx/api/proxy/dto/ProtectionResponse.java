package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Who may reach a site. Usernames only: a password hash is never returned. */
@Schema(name = "ProtectionResponse", requiredProperties = {"mode", "basicUsers"})
public record ProtectionResponse(String mode, List<BasicAuthUserResponse> basicUsers) {

    @Schema(name = "BasicAuthUserResponse", requiredProperties = {"username"})
    public record BasicAuthUserResponse(String username) {
    }
}
