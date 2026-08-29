package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** The listing projection. Carries what the sites table shows and nothing more. */
@Schema(name = "ProxySiteSummaryResponse", description = "A proxy site, for listings.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"id", "name", "domain", "nginxInstanceId", "status", "enabled", "sslEnabled", "upstreamCount", "updatedAt", "version"})
public record ProxySiteSummaryResponse(
        UUID id,
        String name,
        String domain,
        UUID nginxInstanceId,
        String status,
        boolean enabled,
        boolean sslEnabled,
        Instant activeFrom,
        Instant expiresAt,
        Long secondsUntilExpiry,
        int upstreamCount,
        String primaryUpstream,
        Instant updatedAt,
        long version) {
}
