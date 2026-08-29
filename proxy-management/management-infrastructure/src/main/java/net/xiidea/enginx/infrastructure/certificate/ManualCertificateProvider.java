package net.xiidea.enginx.infrastructure.certificate;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.CertificateProvider;
import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.IssuedCertificate;
import org.springframework.stereotype.Component;

/**
 * Certificates an operator supplies themselves.
 *
 * <p>Implements the same interface so the rest of the system does not branch on where material
 * came from: storage, deployment, expiry monitoring and status are identical. Only issuance
 * differs, and this provider cannot do it — which is the honest answer, and why an uploaded
 * certificate is never auto-renewed and will simply be reported as expiring.
 */
@Component
public class ManualCertificateProvider implements CertificateProvider {

    @Override
    public CertificateProviderKind kind() {
        return CertificateProviderKind.MANUAL;
    }

    @Override
    public IssuedCertificate issue(CertificateRequest request) {
        throw new CertificateIssuanceException(
                "This certificate is managed manually. Upload replacement material rather than requesting issuance.",
                false);
    }

    @Override
    public IssuedCertificate renew(Certificate certificate) {
        throw new CertificateIssuanceException(
                "A manually supplied certificate cannot be renewed by the platform. Upload a new one before "
                        + certificate.metadata().notAfter() + ".", false);
    }

    @Override
    public boolean supportsRevocation() {
        // The platform has no relationship with whoever issued it, so it cannot ask for revocation
        // on the operator's behalf. Saying so is better than appearing to revoke and doing nothing.
        return false;
    }

    @Override
    public void revoke(Certificate certificate, String privateKeyPem) {
        throw new CertificateIssuanceException(
                "Revocation must be requested from the authority that issued this certificate.", false);
    }
}
