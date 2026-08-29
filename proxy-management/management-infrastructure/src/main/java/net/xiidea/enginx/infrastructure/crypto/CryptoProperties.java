package net.xiidea.enginx.infrastructure.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @param activeKeyId which configured key wraps new secrets
 * @param keys        key id to base64-encoded 256-bit key; more than one so a rotation can run
 */
@ConfigurationProperties(prefix = "enginx.crypto")
public record CryptoProperties(String activeKeyId, Map<String, String> keys) {

    public CryptoProperties {
        // A blank value means the placeholder behind it was never set, which is how "not
        // configured" is expressed in YAML. Dropping those keeps an unused convenience slot from
        // failing startup, while a genuinely mistyped key still has a value and is still rejected.
        Map<String, String> configured = new LinkedHashMap<>();
        if (keys != null) {
            keys.forEach((id, material) -> {
                if (material != null && !material.isBlank()) {
                    configured.put(id, material);
                }
            });
        }
        keys = Map.copyOf(configured);
    }

    /**
     * Fails fast at startup rather than at the first certificate.
     *
     * <p>An application that starts without a key would appear healthy and then be unable to read
     * any certificate it already holds, which is a far worse way to discover the problem.
     */
    public void requireConfigured() {
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException(
                    "enginx.crypto.active-key-id is not set. Certificate private keys are encrypted at rest and "
                            + "the platform will not start without a key-encryption key.");
        }
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("enginx.crypto.active-key-id is '" + activeKeyId
                    + "' but no key with that id is configured under enginx.crypto.keys. "
                    + "Set it to 32 random bytes, base64 encoded: openssl rand -base64 32");
        }
    }
}
