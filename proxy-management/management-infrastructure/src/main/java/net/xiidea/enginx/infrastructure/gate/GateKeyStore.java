package net.xiidea.enginx.infrastructure.gate;

import net.xiidea.enginx.application.certificate.SecretRewrapTarget;
import net.xiidea.enginx.application.gate.GateKeys;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.deployment.GateMaterialProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Gate keys: issued, sealed, opened, rotated and re-wrapped, in one place.
 *
 * <p>Sealed as base64 text through the same envelope encryption as every other secret, so a key
 * rotation of the KEK reaches them like it reaches certificates. Opened plaintext is cached
 * against the ciphertext it came from, so a rotated key is never served stale.
 */
@Component
public class GateKeyStore implements GateKeys, GateMaterialProvider, SecretRewrapTarget {

    private static final int KEY_BYTES = 32;
    private static final Pattern RELAY_URL =
            Pattern.compile("^https?://[A-Za-z0-9.\\-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$");

    private final JdbcTemplate jdbc;
    private final SecretEncryption encryption;
    private final String relayUrl;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Revealed> revealed = new ConcurrentHashMap<>();

    public GateKeyStore(JdbcTemplate jdbc, SecretEncryption encryption,
                        @Value("${enginx.gate.relay-url:}") String relayUrl) {
        this.jdbc = jdbc;
        this.encryption = encryption;
        this.relayUrl = relayUrl == null ? "" : relayUrl.trim();
        if (!this.relayUrl.isEmpty() && !RELAY_URL.matcher(this.relayUrl).matches()) {
            // Refused at startup: it is written into every gated site's file, and a malformed one
            // would only surface as codes that never arrive.
            throw new IllegalStateException("enginx.gate.relay-url must be a plain http(s) URL with no query, got "
                    + this.relayUrl);
        }
    }

    @Override
    @Transactional
    public void ensure(UUID siteId) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from proxy_site_gate_secrets where proxy_site_id = ?", Integer.class, siteId);
        if (existing != null && existing > 0) {
            return;
        }
        EncryptedSecret sealed = encryption.encrypt(newKey());
        jdbc.update("insert into proxy_site_gate_secrets (proxy_site_id, key_ciphertext, key_wrapped_dek, "
                        + "key_kek_id, key_cipher, key_iv) values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                siteId, sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(), sealed.cipher(), sealed.iv());
    }

    @Override
    @Transactional
    public void rotate(UUID siteId) {
        EncryptedSecret sealed = encryption.encrypt(newKey());
        int updated = jdbc.update("update proxy_site_gate_secrets set key_ciphertext = ?, key_wrapped_dek = ?, "
                        + "key_kek_id = ?, key_cipher = ?, key_iv = ?, rotated_at = now() where proxy_site_id = ?",
                sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(), sealed.cipher(), sealed.iv(), siteId);
        if (updated == 0) {
            ensure(siteId);
        }
        revealed.remove(siteId);
    }

    @Override
    public Optional<byte[]> reveal(UUID siteId) {
        return keyFor(siteId).map(encoded -> Base64.getDecoder().decode(encoded));
    }

    @Override
    public Optional<String> keyFor(UUID siteId) {
        List<EncryptedSecret> rows = jdbc.query("select key_ciphertext, key_wrapped_dek, key_kek_id, key_cipher, key_iv "
                        + "from proxy_site_gate_secrets where proxy_site_id = ?",
                (rs, n) -> new EncryptedSecret(rs.getBytes(1), rs.getBytes(2), rs.getString(3), rs.getString(4),
                        rs.getBytes(5), null),
                siteId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        EncryptedSecret sealed = rows.getFirst();
        Revealed cached = revealed.get(siteId);
        if (cached != null && Arrays.equals(cached.ciphertext(), sealed.ciphertext())) {
            return Optional.of(cached.key());
        }
        String key = encryption.decrypt(sealed);
        revealed.put(siteId, new Revealed(sealed.ciphertext(), key));
        return Optional.of(key);
    }

    @Override
    public Optional<String> relayUrl() {
        return relayUrl.isEmpty() ? Optional.empty() : Optional.of(relayUrl);
    }

    @Override
    public String name() {
        return "Gate keys";
    }

    /** REQUIRES_NEW for the reason given on the ACME account store. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rewrap(String currentKeyId) {
        List<Map<String, Object>> stale = jdbc.queryForList(
                "select proxy_site_id from proxy_site_gate_secrets where key_kek_id <> ?", currentKeyId);
        int moved = 0;
        for (Map<String, Object> row : stale) {
            UUID siteId = (UUID) row.get("proxy_site_id");
            String key = keyFor(siteId).orElseThrow();
            EncryptedSecret sealed = encryption.encrypt(key);
            jdbc.update("update proxy_site_gate_secrets set key_ciphertext = ?, key_wrapped_dek = ?, key_kek_id = ?, "
                            + "key_cipher = ?, key_iv = ? where proxy_site_id = ?",
                    sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(), sealed.cipher(), sealed.iv(), siteId);
            moved++;
        }
        return moved;
    }

    private String newKey() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private record Revealed(byte[] ciphertext, String key) {

        @Override
        public String toString() {
            return "Revealed[redacted]";
        }
    }
}
