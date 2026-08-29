package net.xiidea.enginx.api.proxy.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RenewRequest(@NotNull(message = "A new expiry is required") Instant expiresAt) {
}
