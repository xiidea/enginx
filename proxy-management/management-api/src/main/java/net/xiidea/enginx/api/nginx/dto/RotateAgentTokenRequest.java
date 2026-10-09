package net.xiidea.enginx.api.nginx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A replacement token for a host the platform dials with one.
 *
 * @param agentAuthToken the value now set as AGENT_SECRET_TOKEN on the host. Sealed on arrival
 *                       and never returned
 */
public record RotateAgentTokenRequest(
        @NotBlank(message = "The new agent token is required")
        @Size(max = 512, message = "The agent token is at most 512 characters")
        String agentAuthToken) {
}
