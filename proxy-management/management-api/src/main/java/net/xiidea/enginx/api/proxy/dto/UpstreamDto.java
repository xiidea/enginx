package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "UpstreamDto", description = "One upstream target.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        // Only these three. The rest have server-side defaults and are legitimately omitted when
        // creating a site — this record is used in both directions, and marking a request field
        // required would make the generated client demand a value the API does not.
        requiredProperties = {"scheme", "host", "port"})
public record UpstreamDto(
        String scheme,
        @NotBlank(message = "Upstream host is required") String host,
        @Min(1) @Max(65535) int port,
        Integer weight,
        Integer maxFails,
        Integer failTimeoutSeconds,
        Boolean backup) {
}
