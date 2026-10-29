package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BasicLoginPageDto",
        description = "Basic authentication on the gate's login page. Present only when chosen; absent means the "
                + "browser's own prompt.")
public record BasicLoginPageDto(
        @Schema(description = "How long a sign-in lasts, 300 to 2592000. Defaults to 86400.") Integer sessionTtlSeconds) {
}
