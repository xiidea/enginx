package net.xiidea.enginx.domain.certificate;

/**
 * Obtains certificates from somewhere.
 *
 * <p>The abstraction Phase 1 asked for, so that ACME is one implementation rather than the only
 * possible one. A private CA or a corporate issuance API can be added behind this without the
 * application layer learning about it.
 */
public interface CertificateProvider {

    CertificateProviderKind kind();

    IssuedCertificate issue(CertificateRequest request);

    /**
     * Obtains fresh material for an existing certificate.
     *
     * <p>Separate from {@link #issue} because some authorities treat renewal differently, and
     * because a renewal must cover exactly the domains already recorded — silently changing the
     * coverage would break the sites relying on it.
     */
    IssuedCertificate renew(Certificate certificate);

    boolean supportsRevocation();

    /**
     * @param privateKeyPem the certificate's own key, which most authorities require as proof of
     *                      possession
     */
    void revoke(Certificate certificate, String privateKeyPem);
}
