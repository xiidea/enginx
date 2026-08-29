package net.xiidea.enginx.infrastructure.certificate;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretDecryptionException;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.deployment.CertificateMaterialProvider;
import net.xiidea.enginx.domain.deployment.RenderedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies certificate material to the configuration renderer.
 *
 * <p>This is the one place a private key is decrypted, and the only reason it exists: a bundle
 * bound for an NGINX host has to contain the key. The plaintext lives in a local variable, is
 * marked sensitive the moment it enters a bundle file, and is never logged or returned anywhere
 * else.
 *
 * <p>Material that is expired or revoked is withheld. Deploying it would replace a working
 * certificate with one every client rejects, which is worse than the deployment failing.
 */
@Component
public class StoredCertificateMaterialProvider implements CertificateMaterialProvider {

    private static final Logger log = LoggerFactory.getLogger(StoredCertificateMaterialProvider.class);

    private final CertificateRepository certificates;
    private final SecretEncryption encryption;

    public StoredCertificateMaterialProvider(CertificateRepository certificates, SecretEncryption encryption) {
        this.certificates = certificates;
        this.encryption = encryption;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RenderedCertificate> materialFor(UUID certificateId) {
        if (certificateId == null) {
            return Optional.empty();
        }

        Certificate certificate = certificates.findById(certificateId).orElse(null);
        if (certificate == null || certificate.fullChainPem() == null) {
            return Optional.empty();
        }
        if (!certificate.status().isUsable()) {
            log.warn("Refusing to deploy certificate {} ({}): status is {}",
                    certificateId, certificate.name(), certificate.status());
            return Optional.empty();
        }

        Optional<EncryptedSecret> key = certificates.findPrivateKey(certificateId);
        if (key.isEmpty()) {
            log.warn("Certificate {} has no stored private key and cannot be deployed", certificateId);
            return Optional.empty();
        }

        try {
            return Optional.of(new RenderedCertificate(certificateId,
                    certificate.fullChainPem(), encryption.decrypt(key.get())));
        } catch (SecretDecryptionException e) {
            // Almost always a missing or rotated-away KEK. Loud, because every SSL site on the
            // affected instance will fail to render until it is resolved.
            log.error("Could not decrypt the private key for certificate {} ({}): {}",
                    certificateId, certificate.name(), e.getMessage());
            return Optional.empty();
        }
    }
}
