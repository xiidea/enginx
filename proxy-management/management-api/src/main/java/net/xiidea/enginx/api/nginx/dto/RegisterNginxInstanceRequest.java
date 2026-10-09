package net.xiidea.enginx.api.nginx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param pushTransport        MTLS when absent. A string rather than the enum so a retired or
 *                             mistyped value is answered with what to send instead
 * @param agentCertFingerprint for MTLS only
 * @param agentAuthToken       for HTTP_TOKEN only. Sealed on arrival and never returned
 * @param defaultServerManaged false for a host that keeps its own default server; absent means true
 */
public record RegisterNginxInstanceRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Hostname is required") String hostname,
        @NotBlank(message = "Agent base URL is required") String agentBaseUrl,
        @Schema(allowableValues = {"MTLS", "HTTP_TOKEN"}, defaultValue = "MTLS") String pushTransport,
        String agentCertFingerprint,
        @Size(max = 512, message = "The agent token is at most 512 characters") String agentAuthToken,
        String environment,
        @Schema(defaultValue = "true") Boolean defaultServerManaged) {
}
