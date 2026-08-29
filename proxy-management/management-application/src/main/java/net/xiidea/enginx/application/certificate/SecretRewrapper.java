package net.xiidea.enginx.application.certificate;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-wraps one secret, in its own transaction.
 *
 * <p>A bean of its own rather than a method on {@link SecretRewrapService}, because
 * {@code @Transactional} is applied by a proxy and a self-invoked call never passes through one.
 * Written as a private method on the caller, the REQUIRES_NEW below would silently do nothing, and
 * a single unreadable secret would roll back every secret re-wrapped before it.
 */
@Component
public class SecretRewrapper {

    private static final Logger log = LoggerFactory.getLogger(SecretRewrapper.class);

    private final CertificateRepository certificates;
    private final SecretEncryption encryption;

    public SecretRewrapper(CertificateRepository certificates, SecretEncryption encryption) {
        this.certificates = certificates;
        this.encryption = encryption;
    }

    /**
     * @return whether the secret is now wrapped by the current key
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rewrap(Certificate certificate, EncryptedSecret existing) {
        try {
            String plaintext = encryption.decrypt(existing);
            certificates.storePrivateKey(certificate.id(), encryption.encrypt(plaintext));
            return true;
        } catch (RuntimeException e) {
            // Never the material, and never the cause's own message: a decryption failure can
            // carry provider detail that belongs nowhere near a log.
            log.error("Could not re-wrap the private key for certificate {} ({}): {}",
                    certificate.name(), certificate.id(), e.getClass().getSimpleName());
            return false;
        }
    }
}
