package net.xiidea.enginx.api.nginx.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * @param connectivityMode      PUSH if the platform dials this host, PULL if it calls in
 * @param agentBaseUrl          null for a pull host, which is never dialled
 * @param agentCertFingerprint  null for a pull host, which has no certificate to pin
 */
@Schema(name = "NginxInstanceResponse", description = "A managed NGINX host.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence --
        // and the two agent fields became nullable when pull-mode hosts arrived.
        requiredProperties = {"id", "name", "hostname", "connectivityMode", "environment", "status", "createdAt", "version"})
public record NginxInstanceResponse(
        UUID id,
        String name,
        String hostname,
        String connectivityMode,
        String pushTransport,
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
