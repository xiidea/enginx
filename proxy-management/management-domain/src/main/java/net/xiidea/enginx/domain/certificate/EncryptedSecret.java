package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.ValidationException;

/**
 * A secret at rest, under envelope encryption.
 *
 * <p>The plaintext is encrypted with a data key generated for this secret alone; that data key is
 * then encrypted with a key-encryption key held outside the database. Two properties follow, and
 * both matter: a database dump is useless without the KEK, and the KEK can be rotated by
 * re-wrapping the data keys without touching, or even decrypting, the secrets themselves.
 *
 * @param kekId which key-encryption key wrapped {@code wrappedDataKey}, so rotation can proceed
 *              gradually rather than needing every row rewritten at once
 */
public record EncryptedSecret(
        byte[] ciphertext,
        byte[] wrappedDataKey,
        String kekId,
        String cipher,
        byte[] iv,
        byte[] authTag) {

    public EncryptedSecret {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new ValidationException("ciphertext", "An encrypted secret must have ciphertext");
        }
        if (wrappedDataKey == null || wrappedDataKey.length == 0) {
            throw new ValidationException("wrappedDataKey", "An encrypted secret must carry its wrapped data key");
        }
        if (kekId == null || kekId.isBlank()) {
            throw new ValidationException("kekId", "An encrypted secret must record which KEK wrapped it");
        }
        ciphertext = ciphertext.clone();
        wrappedDataKey = wrappedDataKey.clone();
        iv = iv == null ? new byte[0] : iv.clone();
        authTag = authTag == null ? new byte[0] : authTag.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] wrappedDataKey() {
        return wrappedDataKey.clone();
    }

    @Override
    public byte[] iv() {
        return iv.clone();
    }

    @Override
    public byte[] authTag() {
        return authTag.clone();
    }

    /**
     * Never render a secret, even accidentally. A record's generated toString would print the
     * arrays, and one stray log line is all it takes.
     */
    @Override
    public String toString() {
        return "EncryptedSecret[kekId=" + kekId + ", cipher=" + cipher + ", " + ciphertext.length + " bytes]";
    }
}
