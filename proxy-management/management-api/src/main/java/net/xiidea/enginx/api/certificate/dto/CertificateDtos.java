package net.xiidea.enginx.api.certificate.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CertificateDtos {

    private CertificateDtos() {
    }

    public record RequestAcmeRequest(
            @NotBlank(message = "Name is required") @Size(max = 128) String name,
            @NotEmpty(message = "At least one domain is required") List<String> domains,
            Boolean autoRenew,
            @Min(1) @Max(89) Integer renewBeforeDays) {
    }

    /**
     * @param fullChainPem leaf first, then intermediates, which is the order NGINX requires
     * @param privateKeyPem accepted here and never returned by any endpoint afterwards
     */
    public record UploadRequest(
            @NotBlank(message = "Name is required") @Size(max = 128) String name,
            @NotBlank(message = "Certificate material is required") String fullChainPem,
            @NotBlank(message = "The private key is required") String privateKeyPem) {
    }

    public record ConfigureRenewalRequest(
            boolean autoRenew,
            @Min(1) @Max(89) int renewBeforeDays) {
    }

    /**
     * Certificate metadata.
     *
     * <p>There is deliberately no field for key material, at any permission level. The response
     * type cannot carry a private key, so no handler, mapper or future edit can leak one through
     * this endpoint by accident.
     */
    @Schema(name = "CertificateResponse", description = "One certificate, without any key material.",
            requiredProperties = {"id", "name", "provider", "status", "autoRenew", "renewBeforeDays",
                    "domains", "inUse", "createdBy", "createdAt", "version"})
    public record Response(
            UUID id,
            String name,
            String provider,
            List<String> domains,
            String status,
            String subject,
            String issuer,
            String serialNumber,
            String fingerprintSha256,
            Instant notBefore,
            Instant expiresAt,
            Long daysRemaining,
            boolean autoRenew,
            int renewBeforeDays,
            boolean inUse,
            String lastError,
            Instant revokedAt,
            String createdBy,
            Instant createdAt,
            long version) {
    }

    /**
     * @param examined  certificates looked at
     * @param rewrapped secrets moved to the current key
     * @param failed    secrets that could not be read, and therefore still name their old key
     */
    public record RewrapResponse(int examined, int rewrapped, int failed) {
    }
}
