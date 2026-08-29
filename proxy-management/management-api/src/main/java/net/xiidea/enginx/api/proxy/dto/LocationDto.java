package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LocationDto", description = "A location rule.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"matchType", "pathPattern"})
public record LocationDto(
        @NotBlank(message = "Location path is required") String pathPattern,
        String matchType) {
}
