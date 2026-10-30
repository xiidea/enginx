package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * A location, and how it proxies where that differs from its site. Everything past the match type
 * is optional and the site's when absent, so a client that sends only a path and a match type
 * gets what it always got.
 */
@Schema(name = "LocationDto", description = "A location rule.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"matchType", "pathPattern"})
public record LocationDto(
        @NotBlank(message = "Location path is required") String pathPattern,
        String matchType,
        @Schema(description = "Reached without signing in on a protected site, for a webhook or a health "
                + "endpoint. Not allowed for the root location.")
        Boolean publicAccess,
        @Schema(description = "An upstream pool of its own. Absent or empty: the site's.")
        List<@Valid UpstreamDto> upstreams,
        @Schema(description = "How its own pool is balanced. Only with upstreams of its own.")
        String loadBalancingMethod,
        @Schema(description = "Absent: the site's.") Integer connectTimeoutSeconds,
        @Schema(description = "Absent: the site's.") Integer readTimeoutSeconds,
        @Schema(description = "Absent: the site's.") Integer sendTimeoutSeconds,
        @Schema(description = "Absent: the site's.") Long maxBodySizeBytes,
        @Schema(description = "Absent: the site's.") Boolean websocketEnabled,
        @Schema(description = "Request headers added to the site's; one with the same name replaces the site's.")
        List<@Valid LocationHeaderDto> requestHeaders) {

    /** A location as every location was before they could differ from their site. */
    public LocationDto(String pathPattern, String matchType) {
        this(pathPattern, matchType, null, null, null, null, null, null, null, null, null);
    }

    @Schema(name = "LocationHeaderDto", requiredProperties = {"name", "value"})
    public record LocationHeaderDto(
            @NotBlank(message = "Header name is required") String name,
            String value) {
    }
}
