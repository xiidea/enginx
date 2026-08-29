package net.xiidea.enginx.api.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(name = "HeaderDto", description = "A header rule.",
        // Required means always present and non-null. Nullable fields are left optional
        // on purpose: that is what tells a generated client it must handle their absence.
        requiredProperties = {"direction", "name", "value"})
public record HeaderDto(
        @NotNull(message = "Header direction is required") String direction,
        @NotBlank(message = "Header name is required") String name,
        @NotNull(message = "Header value is required") String value) {
}
