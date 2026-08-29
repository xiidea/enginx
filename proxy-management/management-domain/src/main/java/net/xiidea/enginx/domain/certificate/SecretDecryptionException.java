package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.DomainException;

/**
 * Secret material could not be recovered.
 *
 * <p>Carries no detail about the ciphertext or the key: a decryption failure is one of the few
 * places where a helpful error message is itself an oracle.
 */
public class SecretDecryptionException extends DomainException {

    public SecretDecryptionException(String message) {
        super(message);
    }

    public SecretDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
