package net.xiidea.enginx.api.proxy.dto;

import jakarta.validation.constraints.NotBlank;

public record CloneRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Domain is required") String domain) {
}
