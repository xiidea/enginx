package net.xiidea.enginx.infrastructure.crypto;

import javax.crypto.SecretKey;

/**
 * Wraps and unwraps data keys.
 *
 * <p>An interface from the first line of code, because retrofitting key management is far more
 * expensive than designing the seam (architecture risk R1).
 *
 * <p>It exposes <em>operations</em> rather than the key, which is the whole point. An earlier
 * version returned the key-encryption key itself, and that shape can only ever be implemented by
 * something willing to hand its keys out — which a hardware module, AWS KMS, GCP KMS and Vault's
 * transit engine all specifically refuse to do. Asking the provider to perform the wrap means the
 * key can stay somewhere this process never sees.
 */
public interface KekProvider {

    /** The key new secrets are wrapped with. Recorded per secret so a rotation can be gradual. */
    String currentKeyId();

    /**
     * Wraps a freshly generated data key.
     *
     * @return the key id used and opaque wrapped material, whose format is the provider's business
     */
    WrappedKey wrap(SecretKey dataKey);

    /**
     * Unwraps material produced earlier, by the key that produced it.
     *
     * <p>Rotation depends on this: after the current key changes, existing rows still name the key
     * that wrapped them and must remain readable until they are re-wrapped.
     */
    SecretKey unwrap(String kekId, byte[] wrapped);

    /**
     * Whether this provider can unwrap material bearing this key id.
     *
     * <p>What makes a provider change survivable. Every secret records the key that wrapped it, so
     * during a migration the estate holds a mixture; asking each provider what it recognises lets
     * old secrets stay readable while new ones are written by the new provider. Without it,
     * switching provider makes every existing certificate private key unreadable at once.
     */
    boolean canUnwrap(String kekId);

    record WrappedKey(String kekId, byte[] material) {
    }
}
