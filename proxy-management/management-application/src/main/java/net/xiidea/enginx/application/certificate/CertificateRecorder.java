package net.xiidea.enginx.application.certificate;

import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.IssuedCertificate;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional half of certificate handling.
 *
 * <p>A separate bean rather than methods on {@link CertificateService}, because issuance is a long
 * call to an external authority that must happen <em>outside</em> a transaction, while recording
 * its outcome must happen inside one. Calling an annotated method on {@code this} would skip the
 * proxy entirely and silently give neither: the certificate and its private key are two writes
 * that have to commit together, and half of that pair is a certificate NGINX cannot load.
 */
@Component
public class CertificateRecorder {

    private static final String RESOURCE_TYPE = "CERTIFICATE";

    private final CertificateRepository certificates;
    private final SecretEncryption encryption;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public CertificateRecorder(CertificateRepository certificates, SecretEncryption encryption,
                               AuditRecorder audit, ActorProvider actorProvider, Clock clock) {
        this.certificates = certificates;
        this.encryption = encryption;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    @Transactional
    public Certificate create(CertificateRequest request, CertificateProviderKind kind,
                              boolean autoRenew, int renewBeforeDays) {
        Certificate certificate = Certificate.requested(UUID.randomUUID(), request, kind,
                autoRenew, renewBeforeDays, actorProvider.currentActor().username(), clock.instant());
        Certificate saved = certificates.save(certificate);

        audit.success(AuditAction.CERTIFICATE_REQUESTED, RESOURCE_TYPE, saved.id(), null,
                Map.of("name", request.name(),
                        "domains", String.join(", ", request.domains()),
                        "provider", kind.name()));
        return saved;
    }

    /** Stores material and its key together, or neither. */
    @Transactional
    public Certificate install(UUID certificateId, IssuedCertificate issued, String operation) {
        Certificate certificate = load(certificateId);
        certificate.install(issued, clock.instant());

        Certificate saved = certificates.save(certificate);
        // Encrypted before it reaches storage; the plaintext leaves scope with this method.
        certificates.storePrivateKey(certificateId, encryption.encrypt(issued.privateKeyPem()));

        audit.success(operation.equals("renew") ? AuditAction.CERTIFICATE_RENEWED : AuditAction.CERTIFICATE_ISSUED,
                RESOURCE_TYPE, certificateId, null,
                Map.of("operation", operation,
                        "subject", String.valueOf(issued.metadata().subject()),
                        "issuer", String.valueOf(issued.metadata().issuer()),
                        "notAfter", String.valueOf(issued.metadata().notAfter()),
                        "domains", String.join(", ", issued.metadata().domains())));
        return saved;
    }

    /**
     * Records why issuance failed, keeping any material already installed.
     *
     * <p>A failed renewal must not take a working certificate away from the sites serving it.
     */
    @Transactional
    public Certificate recordFailure(UUID certificateId, String error, String operation) {
        Certificate certificate = load(certificateId);
        certificate.failed(error, clock.instant());
        Certificate saved = certificates.save(certificate);

        audit.failure(AuditAction.CERTIFICATE_FAILED, RESOURCE_TYPE, certificateId, operation + ": " + error);
        return saved;
    }

    private Certificate load(UUID id) {
        return certificates.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }
}
