package net.xiidea.enginx.infrastructure.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key-encryption keys supplied as configuration.
 *
 * <p>The default, and adequate for development and for deployments whose secret management is a
 * mounted file. The key is in this process's memory, which is the limitation a KMS-backed provider
 * exists to remove — see {@link VaultTransitKekProvider}.
 *
 * <p>Registered even when another provider is doing the writing, so that secrets wrapped by it
 * before a migration stay readable. {@link RoutingKekProvider} decides which one writes.
 */
@Component
@EnableConfigurationProperties(CryptoProperties.class)
public class EnvironmentKekProvider implements KekProvider {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final int REQUIRED_KEY_BYTES = 32;

    private final CryptoProperties properties;
    private final Map<String, SecretKey> keys;
    private final SecureRandom random = new SecureRandom();

    /**
     * Decodes and checks every configured key at construction.
     *
     * <p>Eagerly, because both failures it catches are silent otherwise. A key of the wrong length
     * is accepted by AES as a shorter key, so a 16-byte value would quietly give AES-128 where the
     * schema, the column comment and every document say AES-256. And a malformed key discovered at
     * the first certificate is discovered by a user, at the worst moment, rather than at startup.
     */
    public EnvironmentKekProvider(CryptoProperties properties) {
        this.properties = properties;

        Map<String, SecretKey> decoded = new LinkedHashMap<>();
        properties.keys().forEach((id, material) -> {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(material.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("The key-encryption key '" + id
                        + "' is not valid base64. Generate one with: openssl rand -base64 32");
            }
            if (raw.length != REQUIRED_KEY_BYTES) {
                throw new IllegalStateException("The key-encryption key '" + id + "' is "
                        + raw.length + " bytes; it must be exactly " + REQUIRED_KEY_BYTES
                        + " bytes for AES-256. Generate one with: openssl rand -base64 32");
            }
            decoded.put(id, new SecretKeySpec(raw, "AES"));
        });
        this.keys = Map.copyOf(decoded);
    }

    @Override
    public boolean canUnwrap(String kekId) {
        return keys.containsKey(kekId);
    }

    @Override
    public String currentKeyId() {
        return properties.activeKeyId();
    }

    /**
     * The data key is sealed with GCM, so a tampered wrapper fails rather than yielding a wrong
     * key that would go on to produce plausible-looking rubbish.
     */
    @Override
    public WrappedKey wrap(SecretKey dataKey) {
        try {
            SecretKey kek = keyFor(properties.activeKeyId());

            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(dataKey.getEncoded());

            // The wrapper carries its own IV, prefixed, so it is self-describing.
            byte[] wrapped = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, wrapped, 0, iv.length);
            System.arraycopy(sealed, 0, wrapped, iv.length, sealed.length);

            return new WrappedKey(properties.activeKeyId(), wrapped);
        } catch (Exception e) {
            // Never includes the cause's message: a JCE failure can echo key material length
            // and provider detail that belongs nowhere near a log.
            throw new IllegalStateException("Could not wrap the data key: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public SecretKey unwrap(String kekId, byte[] wrapped) {
        // Resolved before the try, so a missing key propagates as itself. Wrapping it would hide
        // the key id behind the deliberately uninformative "could not be decrypted" message, and
        // an operator restoring a lost key needs to know which one.
        SecretKey kek = keyFor(kekId);
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(wrapped, 0, iv, 0, IV_BYTES);
            byte[] sealed = new byte[wrapped.length - IV_BYTES];
            System.arraycopy(wrapped, IV_BYTES, sealed, 0, sealed.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, iv));
            return new SecretKeySpec(cipher.doFinal(sealed), "AES");
        } catch (Exception e) {
            throw new net.xiidea.enginx.domain.certificate.SecretDecryptionException(
                    "Could not unwrap the data key with key " + kekId, e);
        }
    }

    private SecretKey keyFor(String kekId) {
        SecretKey key = keys.get(kekId);
        if (key == null) {
            // Names the id, never the material.
            throw new IllegalStateException("No key-encryption key is configured with id '" + kekId
                    + "'. Secrets wrapped with it cannot be read until it is restored.");
        }
        return key;
    }
}
