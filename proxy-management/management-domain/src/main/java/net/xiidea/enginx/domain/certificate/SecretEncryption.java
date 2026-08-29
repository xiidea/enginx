package net.xiidea.enginx.domain.certificate;

/**
 * Encrypts and decrypts secret material.
 *
 * <p>A port, so the domain can say "this is stored encrypted" without knowing which cipher or key
 * manager is behind it. The implementation decides; a deployment can move from an environment
 * variable to a KMS without any change above this line.
 */
public interface SecretEncryption {

    EncryptedSecret encrypt(String plaintext);

    /**
     * @throws SecretDecryptionException when the material cannot be recovered, which usually
     *                                   means the KEK that wrapped it is no longer available
     */
    String decrypt(EncryptedSecret secret);

    /**
     * The key id new secrets are being wrapped with.
     *
     * <p>Part of this contract rather than of the provider's, because the only caller that needs
     * it is the re-wrap operation, and it should not have to reach past this port into whatever
     * key management a deployment happens to use.
     */
    String currentKeyId();
}
