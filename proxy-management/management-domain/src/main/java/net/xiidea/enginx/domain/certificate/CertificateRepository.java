package net.xiidea.enginx.domain.certificate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CertificateRepository {

    Certificate save(Certificate certificate);

    Optional<Certificate> findById(UUID id);

    List<Certificate> findAll();

    /** Certificates due for renewal, for the monitoring job. */
    List<Certificate> findDueForRenewal(Instant now, int limit);

    /** Certificates whose derived status has drifted from what is stored. */
    List<Certificate> findWithStaleStatus(Instant now, int limit);

    boolean isInUse(UUID certificateId);

    void deleteById(UUID id);

    // ---- secret material -------------------------------------------------

    /**
     * Stores the private key. Separate from {@link #save} so that writing a certificate can never
     * accidentally rewrite, or blank, the key that goes with it.
     */
    void storePrivateKey(UUID certificateId, EncryptedSecret key);

    /**
     * The private key, still encrypted.
     *
     * <p>The only path to key material. It returns the encrypted form so that decryption is an
     * explicit, separate act at the one point that needs it — building a deployment bundle.
     */
    Optional<EncryptedSecret> findPrivateKey(UUID certificateId);
}
