package net.xiidea.enginx.api.nginx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A replacement agent certificate to trust.
 *
 * <p>Only the fingerprint. The platform never holds an agent's certificate or key — it verifies
 * the chain against the CA and then pins this digest, so rotating is a matter of trusting a new
 * digest rather than of transferring any material.
 *
 * @param agentCertFingerprint SHA-256, 64 hex characters. Colons and spaces are tolerated and
 *                             stripped, because that is how every tool prints one
 */
public record RotateAgentCertificateRequest(
        @NotBlank(message = "The new agent certificate fingerprint is required")
        @Size(max = 95, message = "A SHA-256 fingerprint is 64 hex characters")
        String agentCertFingerprint) {
}
