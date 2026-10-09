package net.xiidea.enginx.api.nginx.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Who answers names no site on the host matches.
 *
 * @param managed true for the platform's catch-all; false when the host keeps its own default
 *                server, which it must then provide
 */
public record SetDefaultServerRequest(@NotNull(message = "managed is required") Boolean managed) {
}
