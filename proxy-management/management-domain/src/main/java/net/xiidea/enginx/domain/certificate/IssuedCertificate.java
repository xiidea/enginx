package net.xiidea.enginx.domain.certificate;

/**
 * Freshly issued material, on its way to storage.
 *
 * <p>Deliberately short-lived: it holds a private key in memory and is expected to be encrypted
 * and discarded within a single call.
 *
 * @param fullChainPem leaf first, then intermediates, which is the order NGINX requires
 */
public record IssuedCertificate(String fullChainPem, String privateKeyPem, CertificateMetadata metadata) {

    public static IssuedCertificate of(String fullChainPem, String privateKeyPem) {
        return new IssuedCertificate(fullChainPem, privateKeyPem, CertificateMetadata.parse(fullChainPem));
    }

    @Override
    public String toString() {
        return "IssuedCertificate[subject=" + metadata.subject() + ", notAfter=" + metadata.notAfter() + "]";
    }
}
