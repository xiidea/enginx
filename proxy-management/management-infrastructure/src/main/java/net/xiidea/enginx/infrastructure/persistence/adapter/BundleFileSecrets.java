package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.application.certificate.SecretRewrapTarget;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sealing for the sensitive files of stored bundles: private keys, htpasswd files, gate files.
 *
 * <p>A bundle is kept as rendered so a redeploy or a rollback resends exactly what was validated.
 * Its sensitive files are therefore secrets in their own right, and are held the way every other
 * secret is: envelope-encrypted, re-wrapped when the key rotates, opened only to be sent to a host.
 * Opened content is cached against the ciphertext it came from, bounded, so a deployment does not
 * pay a key unwrap — a network round trip under Vault — for every file every time.
 */
@Component
public class BundleFileSecrets implements SecretRewrapTarget {

    private static final Logger log = LoggerFactory.getLogger(BundleFileSecrets.class);
    private static final int MAX_CACHED = 512;

    private final SecretEncryption encryption;
    private final JdbcTemplate jdbc;
    private final Map<UUID, Revealed> revealed = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Revealed> eldest) {
            return size() > MAX_CACHED;
        }
    };

    public BundleFileSecrets(SecretEncryption encryption, JdbcTemplate jdbc) {
        this.encryption = encryption;
        this.jdbc = jdbc;
    }

    EncryptedSecret seal(String content) {
        return encryption.encrypt(content);
    }

    String reveal(UUID fileId, EncryptedSecret sealed) {
        synchronized (revealed) {
            Revealed cached = revealed.get(fileId);
            if (cached != null && Arrays.equals(cached.ciphertext(), sealed.ciphertext())) {
                return cached.content();
            }
        }
        String content = encryption.decrypt(sealed);
        synchronized (revealed) {
            revealed.put(fileId, new Revealed(sealed.ciphertext(), content));
        }
        return content;
    }

    /**
     * Seals sensitive files stored before sealing existed, then has the database hold every row to
     * it. Runs once per start; a second run finds nothing to do.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sealStoredPlaintext() {
        int sealed = sealPlaintext();
        if (sealed > 0) {
            log.info("Sealed {} sensitive bundle file(s) that were stored in the clear", sealed);
        }
        try {
            jdbc.execute("ALTER TABLE config_bundle_files VALIDATE CONSTRAINT ck_bundle_file_sensitive_sealed");
        } catch (RuntimeException e) {
            // Not fatal: new rows are held to it either way. Validating needs the table's owner.
            log.warn("Could not validate ck_bundle_file_sensitive_sealed: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sealPlaintext() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, content from config_bundle_files where sensitive and content is not null");
        for (Map<String, Object> row : rows) {
            EncryptedSecret sealed = seal((String) row.get("content"));
            store((UUID) row.get("id"), sealed);
        }
        return rows.size();
    }

    @Override
    public String name() {
        return "Sensitive bundle files";
    }

    /** REQUIRES_NEW for the reason given on the ACME account store. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rewrap(String currentKeyId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, content_ciphertext, content_wrapped_dek, content_kek_id, content_cipher, content_iv "
                        + "from config_bundle_files where content_ciphertext is not null and content_kek_id <> ?",
                currentKeyId);
        for (Map<String, Object> row : rows) {
            EncryptedSecret old = new EncryptedSecret((byte[]) row.get("content_ciphertext"),
                    (byte[]) row.get("content_wrapped_dek"), (String) row.get("content_kek_id"),
                    (String) row.get("content_cipher"), (byte[]) row.get("content_iv"), null);
            store((UUID) row.get("id"), encryption.encrypt(encryption.decrypt(old)));
        }
        return rows.size();
    }

    private void store(UUID id, EncryptedSecret sealed) {
        jdbc.update("update config_bundle_files set content = null, content_ciphertext = ?, content_wrapped_dek = ?, "
                        + "content_kek_id = ?, content_cipher = ?, content_iv = ? where id = ?",
                sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(), sealed.cipher(), sealed.iv(), id);
    }

    private record Revealed(byte[] ciphertext, String content) {

        @Override
        public String toString() {
            return "Revealed[redacted]";
        }
    }
}
