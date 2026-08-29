package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretDecryptionException;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Envelope encryption with AES-256-GCM.
 *
 * <p>Each secret gets its own data key, which is encrypted with the current key-encryption key.
 * Two consequences: a database dump is inert without the KEK, and rotating the KEK means
 * re-wrapping small data keys rather than decrypting and re-encrypting every secret.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts. Without that, ciphertext in
 * a table an attacker can write to could be altered into different plaintext, and the first thing
 * to notice would be NGINX loading a key nobody chose.
 */
@Service
public class EnvelopeEncryptionService implements SecretEncryption {

    private static final String CIPHER = "AES-256-GCM";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KekProvider keks;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeEncryptionService(KekProvider keks) {
        this.keks = keks;
    }

    @Override
    public EncryptedSecret encrypt(String plaintext) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BITS, random);
            SecretKey dataKey = generator.generateKey();

            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Wrapping is the provider's job, so a KMS-backed one can perform it without ever
            // handing this process the key that does it.
            KekProvider.WrappedKey wrapped = keks.wrap(dataKey);

            return new EncryptedSecret(sealed, wrapped.material(), wrapped.kekId(), CIPHER, iv, new byte[0]);
        } catch (Exception e) {
            // The message must not describe the plaintext or the key.
            throw new IllegalStateException("Could not encrypt secret material: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public String currentKeyId() {
        return keks.currentKeyId();
    }

    @Override
    public String decrypt(EncryptedSecret secret) {
        try {
            SecretKey dataKey = keks.unwrap(secret.kekId(), secret.wrappedDataKey());

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(TAG_BITS, secret.iv()));
            return new String(cipher.doFinal(secret.ciphertext()), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw new SecretDecryptionException(e.getMessage(), e);
        } catch (Exception e) {
            // Deliberately uninformative. Distinguishing "wrong key" from "tampered ciphertext"
            // would tell an attacker which of the two they achieved.
            throw new SecretDecryptionException("Secret material could not be decrypted", e);
        }
    }
}
