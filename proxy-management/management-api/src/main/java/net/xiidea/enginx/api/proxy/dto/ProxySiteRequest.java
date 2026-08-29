package net.xiidea.enginx.api.proxy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Create and update payload.
 *
 * <p>Bean validation here only catches the cheap, shape-level errors so that the client gets a
 * complete field list in one response. The real invariants live in the domain value objects,
 * which every path into the system must pass through.
 */
public record ProxySiteRequest(
        @NotBlank(message = "Name is required") @Size(max = 128) String name,
        @NotBlank(message = "Domain is required") @Size(max = 253) String domain,
        @NotNull(message = "An NGINX instance must be selected") UUID nginxInstanceId,
        Instant activeFrom,
        Instant expiresAt,
        Boolean enabled,
        Boolean sslEnabled,
        Boolean forceHttps,
        Boolean hstsEnabled,
        Boolean websocketEnabled,
        UUID sslCertificateId,
        String loadBalancingMethod,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Integer sendTimeoutSeconds,
        Long maxBodySizeBytes,
        @NotEmpty(message = "At least one upstream is required") @Valid List<UpstreamDto> upstreams,
        @Valid List<HeaderDto> headers,
        @Valid List<LocationDto> locations) {
}
