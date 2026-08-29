package net.xiidea.enginx.domain.deployment;

import java.util.List;

/**
 * What the host reports about itself.
 *
 * <p>{@code activeBundleId} is what the reconciler compares against the database's belief. A
 * mismatch means someone changed the host by hand or it was rebuilt, and that is a condition to
 * report rather than to silently overwrite.
 */
public record AgentStatus(
        String agentVersion,
        String nginxVersion,
        boolean nginxRunning,
        String activeBundleId,
        boolean configTestOk,
        String configTestOutput,
        List<CertificateStatus> certificates) {

    public record CertificateStatus(String path, String subject, String notAfter, int daysRemaining, String status) {
    }
}
