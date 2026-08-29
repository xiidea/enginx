package net.xiidea.enginx.application.certificate;

import java.util.List;
import java.util.UUID;

public final class CertificateCommands {

    private CertificateCommands() {
    }

    public record RequestAcme(String name, List<String> domains, boolean autoRenew, int renewBeforeDays) {
    }

    /**
     * @param fullChainPem leaf first, then intermediates. The domains and dates are read from the
     *                     certificate itself, never taken from the request.
     */
    public record Upload(String name, String fullChainPem, String privateKeyPem) {
    }

    public record Replace(UUID certificateId, String fullChainPem, String privateKeyPem) {
    }

    public record ConfigureRenewal(UUID certificateId, boolean autoRenew, int renewBeforeDays) {
    }
}
