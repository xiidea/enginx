package net.xiidea.enginx.api.nginx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "NginxInstanceResponse", description = "A managed NGINX host.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"id", "name", "hostname", "agentBaseUrl", "agentCertFingerprint", "environment", "status", "createdAt", "version"})
public record NginxInstanceResponse(
        UUID id,
        String name,
        String hostname,
        String agentBaseUrl,
        String agentCertFingerprint,
        String environment,
        String status,
        String nginxVersion,
        String agentVersion,
        Instant lastSeenAt,
        Instant createdAt,
        long version) {
}
