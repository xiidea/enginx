package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "ProxySiteResponse", description = "One proxy site in full.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"id", "name", "domain", "nginxInstanceId", "status", "enabled", "sslEnabled", "forceHttps", "hstsEnabled", "websocketEnabled", "loadBalancingMethod", "connectTimeoutSeconds", "readTimeoutSeconds", "sendTimeoutSeconds", "maxBodySizeBytes", "upstreams", "headers", "locations", "createdBy", "createdAt", "updatedBy", "updatedAt", "version"})
public record ProxySiteResponse(
        UUID id,
        String name,
        String domain,
        UUID nginxInstanceId,
        String status,
        boolean enabled,
        Instant activeFrom,
        Instant expiresAt,
        Long secondsUntilExpiry,
        boolean sslEnabled,
        boolean forceHttps,
        boolean hstsEnabled,
        boolean websocketEnabled,
        UUID sslCertificateId,
        String loadBalancingMethod,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int sendTimeoutSeconds,
        long maxBodySizeBytes,
        List<UpstreamDto> upstreams,
        List<HeaderDto> headers,
        List<LocationDto> locations,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt,
        long version) {
}
