package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretDecryptionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The encryption that stands between a database dump and every private key the platform holds.
 */
class EnvelopeEncryptionServiceTest {

    private static final String PRIVATE_KEY =
            "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq...\n-----END PRIVATE KEY-----\n";

    @Test
    void roundTripsSecretMaterial() {
        EnvelopeEncryptionService service = serviceWith("k1");

        EncryptedSecret sealed = service.encrypt(PRIVATE_KEY);

        assertThat(service.decrypt(sealed)).isEqualTo(PRIVATE_KEY);
    }

    @Test
    @DisplayName("the plaintext never appears in the stored bytes")
    void ciphertextDoesNotLeakThePlaintext() {
        EncryptedSecret sealed = serviceWith("k1").encrypt(PRIVATE_KEY);

        assertThat(new String(sealed.ciphertext())).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(sealed.ciphertext()).isNotEqualTo(PRIVATE_KEY.getBytes());
    }

    @Test
    @DisplayName("encrypting the same secret twice produces different ciphertext")
    void encryptionIsNotDeterministic() {
        EnvelopeEncryptionService service = serviceWith("k1");

        EncryptedSecret first = service.encrypt(PRIVATE_KEY);
        EncryptedSecret second = service.encrypt(PRIVATE_KEY);

        // A fresh data key and IV each time, so identical secrets are not identifiable as such
        // from the stored rows.
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.wrappedDataKey()).isNotEqualTo(second.wrappedDataKey());
    }

    @Test
    @DisplayName("tampered ciphertext is rejected rather than decrypted into something else")
    void tamperingIsDetected() {
        EnvelopeEncryptionService service = serviceWith("k1");
        EncryptedSecret sealed = service.encrypt(PRIVATE_KEY);

        byte[] altered = sealed.ciphertext();
        altered[0] ^= 0x01;
        EncryptedSecret tampered = new EncryptedSecret(altered, sealed.wrappedDataKey(),
                sealed.kekId(), sealed.cipher(), sealed.iv(), sealed.authTag());

        // GCM authenticates, so a modified row cannot be turned into a different valid key.
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    @DisplayName("a tampered wrapped data key is rejected too")
    void tamperingWithTheWrappedKeyIsDetected() {
        EnvelopeEncryptionService service = serviceWith("k1");
        EncryptedSecret sealed = service.encrypt(PRIVATE_KEY);

        byte[] altered = sealed.wrappedDataKey();
        altered[altered.length - 1] ^= 0x01;
        EncryptedSecret tampered = new EncryptedSecret(sealed.ciphertext(), altered,
                sealed.kekId(), sealed.cipher(), sealed.iv(), sealed.authTag());

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    @DisplayName("the wrong key-encryption key cannot read the secret")
    void aDifferentKekCannotDecrypt() {
        EncryptedSecret sealed = serviceWith("k1").encrypt(PRIVATE_KEY);

        // Same key id, different key material: what an attacker with the database but not the
        // key manager would have.
        EnvelopeEncryptionService imposter = serviceWith("k1");

        assertThatThrownBy(() -> imposter.decrypt(sealed))
                .isInstanceOf(SecretDecryptionException.class);
    }

    @Test
    @DisplayName("rotation keeps old secrets readable while new ones use the new key")
    void rotationIsGradual() throws NoSuchAlgorithmException {
        SecretKey old = generateKey();
        SecretKey fresh = generateKey();

        EnvelopeEncryptionService before = new EnvelopeEncryptionService(
                new EnvironmentKekProvider(new CryptoProperties("k1", Map.of("k1", encode(old)))));
        EncryptedSecret sealedWithOld = before.encrypt(PRIVATE_KEY);
        assertThat(sealedWithOld.kekId()).isEqualTo("k1");

        // The new key becomes current; the old one stays configured so what it wrapped is still
        // readable. Re-wrapping can then happen in the background rather than as a big-bang.
        EnvelopeEncryptionService after = new EnvelopeEncryptionService(
                new EnvironmentKekProvider(new CryptoProperties("k2",
                        Map.of("k1", encode(old), "k2", encode(fresh)))));

        assertThat(after.decrypt(sealedWithOld)).isEqualTo(PRIVATE_KEY);
        assertThat(after.encrypt(PRIVATE_KEY).kekId()).isEqualTo("k2");
    }

    @Test
    @DisplayName("a secret whose key is no longer configured fails loudly and names the key")
    void missingKekIsReported() {
        EncryptedSecret sealed = serviceWith("k1").encrypt(PRIVATE_KEY);
        EnvelopeEncryptionService withoutThatKey = serviceWith("k2");

        assertThatThrownBy(() -> withoutThatKey.decrypt(sealed))
                .isInstanceOf(SecretDecryptionException.class)
                .hasMessageContaining("k1");
    }

    @Test
    @DisplayName("the platform refuses to start without a key, rather than failing at the first certificate")
    void missingConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> new CryptoProperties(null, Map.of()).requireConfigured())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-id");

        assertThatThrownBy(() -> new CryptoProperties("k1", Map.of()).requireConfigured())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no key with that id");
    }

    @Test
    @DisplayName("a key of the wrong length is refused instead of silently weakening encryption")
    void shortKeysAreRefused() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EnvironmentKekProvider(new CryptoProperties("k1", Map.of("k1", tooShort))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("an encrypted secret does not print its contents")
    void toStringHidesTheMaterial() {
        EncryptedSecret sealed = serviceWith("k1").encrypt(PRIVATE_KEY);

        assertThat(sealed.toString()).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(sealed.toString()).contains("kekId=k1");
    }

    private static EnvelopeEncryptionService serviceWith(String keyId) {
        try {
            return new EnvelopeEncryptionService(new EnvironmentKekProvider(
                    new CryptoProperties(keyId, Map.of(keyId, encode(generateKey())))));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static SecretKey generateKey() throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private static String encode(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
