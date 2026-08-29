package net.xiidea.enginx.application.certificate;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Re-encrypts stored secrets under the current key-encryption key.
 *
 * <p>The operation that makes two documented promises actually true. The schema records a key id
 * per secret specifically so "a rotation can proceed gradually" — but nothing re-wrapped anything,
 * so a rotation could begin and never finish, and the old key could never be retired. The same
 * gap made adopting a KMS impossible: new secrets would use it while every existing one stayed
 * tied to a key the deployment was trying to stop using.
 *
 * <p>Decryption goes through the routing provider, so secrets wrapped by a previous provider are
 * readable as long as that provider is still configured. Re-wrapping is therefore: configure the
 * new provider, keep the old one, run this, then remove the old one.
 */
@Service
public class SecretRewrapService {

    private static final Logger log = LoggerFactory.getLogger(SecretRewrapService.class);
    private static final String RESOURCE_TYPE = "CERTIFICATE_SECRET";

    private final CertificateRepository certificates;
    private final SecretEncryption encryption;
    private final SecretRewrapper rewrapper;
    private final List<SecretRewrapTarget> otherStores;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;

    public SecretRewrapService(CertificateRepository certificates, SecretEncryption encryption,
                               SecretRewrapper rewrapper, List<SecretRewrapTarget> otherStores,
                               SitePermissionService permissions, AuditRecorder audit) {
        this.certificates = certificates;
        this.encryption = encryption;
        this.rewrapper = rewrapper;
        this.otherStores = otherStores;
        this.permissions = permissions;
        this.audit = audit;
    }

    /**
     * Re-wraps every certificate private key that is not already under the current key.
     *
     * @return what happened, so an operator can tell "nothing needed doing" from "nothing worked"
     */
    @Transactional(readOnly = true)
    public Result rewrapAll() {
        permissions.requireGlobalAdmin();
        String currentKeyId = encryption.currentKeyId();

        int examined = 0;
        int rewrapped = 0;
        int failed = 0;

        for (Certificate certificate : certificates.findAll()) {
            examined++;
            EncryptedSecret existing = certificates.findPrivateKey(certificate.id()).orElse(null);
            if (existing == null || currentKeyId.equals(existing.kekId())) {
                continue;
            }
            if (rewrapper.rewrap(certificate, existing)) {
                rewrapped++;
            } else {
                failed++;
            }
        }

        // Everything else that holds a wrapped secret — the ACME account key above all. Leaving
        // it behind would keep the old key required by a single row that nobody would think to
        // look for until issuance stopped working.
        for (SecretRewrapTarget store : otherStores) {
            try {
                int moved = store.rewrap(currentKeyId);
                rewrapped += moved;
                examined += moved;
            } catch (RuntimeException e) {
                failed++;
                log.error("Could not re-wrap secrets in {}: {}", store.name(), e.getClass().getSimpleName());
            }
        }

        log.info("Secret re-wrap finished: {} examined, {} re-wrapped, {} failed", examined, rewrapped, failed);
        audit.success(AuditAction.CERTIFICATE_SECRETS_REWRAPPED, RESOURCE_TYPE, null, null,
                Map.of("examined", String.valueOf(examined),
                        "rewrapped", String.valueOf(rewrapped),
                        "failed", String.valueOf(failed),
                        "keyId", currentKeyId));

        return new Result(examined, rewrapped, failed);
    }

    /**
     * @param examined  certificates looked at
     * @param rewrapped secrets moved to the current key
     * @param failed    secrets that could not be read, and therefore still name their old key
     */
    public record Result(int examined, int rewrapped, int failed) {
    }
}
