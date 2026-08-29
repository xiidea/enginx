package net.xiidea.enginx.infrastructure.crypto;

import net.xiidea.enginx.domain.certificate.SecretDecryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Wraps data keys with HashiCorp Vault's transit engine.
 *
 * <p>The key-encryption key is created inside Vault and never leaves it. This process sends a data
 * key to be wrapped and receives opaque ciphertext; it cannot decrypt anything on its own, and a
 * copy of the database plus a copy of this application's configuration is not enough to read a
 * single certificate private key. That is the property {@link EnvironmentKekProvider} cannot offer
 * at any level of care, because the key is in its memory by construction.
 *
 * <p>Rotation is Vault's: {@code vault write -f transit/keys/<name>/rotate} moves new wraps to a
 * new version while old ciphertext stays readable. The {@code kek_id} recorded per secret is the
 * transit key name, so this provider's own notion of rotation is deliberately coarse — the version
 * lives inside the ciphertext, where Vault manages it.
 */
@Component
// Not @ConditionalOnProperty: that matches on a property *existing*, and an unset environment
// variable behind a placeholder default leaves an empty string. The bean would then be created
// for every deployment that has no Vault, and fail startup demanding a token nobody needs. The
// same trap caught the notification webhook channel in Phase 10.
@ConditionalOnExpression("!'${enginx.crypto.vault.address:}'.trim().isEmpty()")
public class VaultTransitKekProvider implements KekProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultTransitKekProvider.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final URI address;
    private final String token;
    private final String keyName;
    private final HttpClient client;

    public VaultTransitKekProvider(
            @Value("${enginx.crypto.vault.address}") String address,
            @Value("${enginx.crypto.vault.token}") String token,
            @Value("${enginx.crypto.vault.transit-key:enginx}") String keyName) {

        if (address == null || address.isBlank() || token == null || token.isBlank()) {
            // Refuse at startup rather than at the first certificate. An application that came up
            // and could then read nothing it already held would be a far worse way to find out.
            throw new IllegalStateException(
                    "enginx.crypto.vault.address is set but enginx.crypto.vault.token is not.");
        }
        this.address = URI.create(address.trim().replaceAll("/+$", ""));
        this.token = token.trim();
        this.keyName = keyName;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        log.info("Data keys will be wrapped by Vault transit key '{}' at {}", keyName, this.address);
    }

    @Override
    public boolean canUnwrap(String kekId) {
        return keyName.equals(kekId);
    }

    @Override
    public String currentKeyId() {
        return keyName;
    }

    @Override
    public WrappedKey wrap(SecretKey dataKey) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("plaintext", Base64.getEncoder().encodeToString(dataKey.getEncoded()));

        JsonNode data = call("/v1/transit/encrypt/" + keyName, payload, "wrapping a data key");
        String ciphertext = data.path("ciphertext").asString();

        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalStateException("Vault returned no ciphertext when wrapping a data key");
        }
        // Vault's ciphertext is a self-describing string, "vault:v1:...". Stored as bytes because
        // that is what the column holds; it is never parsed here.
        return new WrappedKey(keyName, ciphertext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public SecretKey unwrap(String kekId, byte[] wrapped) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("ciphertext", new String(wrapped, StandardCharsets.UTF_8));

        // Decrypted by the key the secret names, not by the current one: after a rotation the old
        // ciphertext must stay readable, and Vault resolves the version from the ciphertext itself.
        JsonNode data = call("/v1/transit/decrypt/" + kekId, payload, "unwrapping a data key");
        String plaintext = data.path("plaintext").asString();

        if (plaintext == null || plaintext.isBlank()) {
            throw new SecretDecryptionException("Vault returned no plaintext when unwrapping a data key");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(plaintext), "AES");
    }

    private JsonNode call(String path, ObjectNode payload, String what) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // The body can echo the request, which for an encrypt call is the data key. Only
                // the status is reported; the rest would be a key in a log line.
                throw new IllegalStateException(
                        "Vault returned " + response.statusCode() + " while " + what);
            }
            return JSON.readTree(response.body()).path("data");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while " + what);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reach Vault while " + what + ": " + e.getMessage());
        }
    }
}
