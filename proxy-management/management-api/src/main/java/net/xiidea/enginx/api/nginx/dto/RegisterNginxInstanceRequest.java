package net.xiidea.enginx.api.nginx.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterNginxInstanceRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Hostname is required") String hostname,
        @NotBlank(message = "Agent base URL is required") String agentBaseUrl,
        String pushTransport,
        String agentCertFingerprint,
        String agentAuthToken,
        String environment) {
}
