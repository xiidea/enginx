package net.xiidea.enginx.application.certificate;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.CertificateProvider;
import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.IssuedCertificate;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Certificate use cases.
 *
 * <p>Issuance talks to an external authority, which can take tens of seconds and cannot be
 * rolled back. It therefore runs outside the database transaction that records the result: the
 * alternative is holding a transaction open across a network call to a third party, and a
 * rollback that cannot undo a certificate that has already been issued and counted against a rate
 * limit.
 */
@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);
    private static final String RESOURCE_TYPE = "CERTIFICATE";

    private final CertificateRepository certificates;
    private final SecretEncryption encryption;
    private final Map<CertificateProviderKind, CertificateProvider> providers = new HashMap<>();
    private final CertificateRecorder recorder;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final Clock clock;

    public CertificateService(CertificateRepository certificates,
                              SecretEncryption encryption,
                              List<CertificateProvider> availableProviders,
                              CertificateRecorder recorder,
                              SitePermissionService permissions,
                              AuditRecorder audit,
                              ActorProvider actorProvider,
                              Clock clock) {
        this.certificates = certificates;
        this.encryption = encryption;
        availableProviders.forEach(provider -> providers.put(provider.kind(), provider));
        this.recorder = recorder;
        this.permissions = permissions;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Certificate> findAll() {
        permissions.requireCertificateVisibility();
        return certificates.findAll();
    }

    @Transactional(readOnly = true)
    public Certificate get(UUID id) {
        permissions.requireCertificateVisibility();
        return load(id);
    }

    /**
     * Requests a certificate from an ACME authority.
     *
     * <p>The record is created and committed first, so that a failure leaves an auditable
     * certificate in ERROR carrying the reason, rather than nothing at all. An operator needs to
     * see that the attempt happened and why it did not work.
     */
    public Certificate requestAcme(CertificateCommands.RequestAcme command) {
        CertificateRequest request = CertificateRequest.of(command.name(), command.domains());
        command.domains().forEach(permissions::requireCertificateAuthorityOver);

        Certificate certificate = recorder.create(request, CertificateProviderKind.ACME,
                command.autoRenew(), command.renewBeforeDays());

        return issueInto(certificate, provider(CertificateProviderKind.ACME), request, "issue");
    }

    /** Obtains fresh material for an existing ACME certificate. */
    public Certificate renew(UUID id) {
        Certificate certificate = load(id);
        certificate.domains().forEach(permissions::requireCertificateAuthorityOver);
        return renewInternal(certificate);
    }

    /**
     * Renews on the platform's own behalf, from the monitoring job.
     *
     * <p>No permission check: there is no user, and the renewal was authorised when someone
     * enabled automatic renewal on this certificate. Deliberately not reachable from the API.
     */
    public Certificate renewAsSystem(UUID id) {
        return renewInternal(load(id));
    }

    private Certificate renewInternal(Certificate certificate) {

        if (certificate.provider() != CertificateProviderKind.ACME) {
            throw new ConflictException(
                    "This certificate is managed manually. Upload replacement material instead.");
        }
        return issueInto(certificate, provider(CertificateProviderKind.ACME), null, "renew");
    }

    /**
     * Runs issuance outside a transaction, then records the outcome in one.
     *
     * @param request null for a renewal, which reuses the certificate's recorded domains
     */
    private Certificate issueInto(Certificate certificate, CertificateProvider provider,
                                  CertificateRequest request, String operation) {
        try {
            IssuedCertificate issued = request == null
                    ? provider.renew(certificate)
                    : provider.issue(request);
            return recorder.install(certificate.id(), issued, operation);

        } catch (CertificateIssuanceException e) {
            log.warn("Certificate {} failed to {}: {}", certificate.id(), operation, e.getMessage());
            return recorder.recordFailure(certificate.id(), e.getMessage(), operation);
        }
    }

    /** Stores material an operator supplied. */
    @Transactional
    public Certificate upload(CertificateCommands.Upload command) {
        IssuedCertificate issued = IssuedCertificate.of(command.fullChainPem(), command.privateKeyPem());
        requireKeyMatchesCertificate(issued);
        issued.metadata().domains().forEach(permissions::requireCertificateAuthorityOver);

        CertificateRequest request = new CertificateRequest(command.name(), issued.metadata().domains());
        // Never auto-renewed: the platform has no relationship with whoever issued it.
        Certificate certificate = Certificate.requested(UUID.randomUUID(), request,
                CertificateProviderKind.MANUAL, false, 30, actor(), clock.instant());
        certificate.install(issued, clock.instant());

        Certificate saved = certificates.save(certificate);
        certificates.storePrivateKey(saved.id(), encryption.encrypt(command.privateKeyPem()));

        audit.success(AuditAction.CERTIFICATE_UPLOADED, RESOURCE_TYPE, saved.id(), null,
                Map.of("name", command.name(),
                        "domains", String.join(", ", issued.metadata().domains()),
                        "notAfter", String.valueOf(issued.metadata().notAfter())));
        return saved;
    }

    @Transactional
    public Certificate configureRenewal(CertificateCommands.ConfigureRenewal command) {
        Certificate certificate = load(command.certificateId());
        certificate.domains().forEach(permissions::requireCertificateAuthorityOver);

        if (command.autoRenew() && certificate.provider() != CertificateProviderKind.ACME) {
            throw new ValidationException("autoRenew",
                    "Only certificates obtained over ACME can be renewed automatically");
        }
        certificate.configureRenewal(command.autoRenew(), command.renewBeforeDays(), clock.instant());
        return certificates.save(certificate);
    }

    @Transactional
    public Certificate revoke(UUID id) {
        Certificate certificate = load(id);
        certificate.domains().forEach(permissions::requireCertificateAuthorityOver);

        CertificateProvider provider = provider(certificate.provider());
        if (provider.supportsRevocation()) {
            EncryptedSecret key = certificates.findPrivateKey(id)
                    .orElseThrow(() -> new ConflictException(
                            "The private key for this certificate is missing, so it cannot be revoked"));
            provider.revoke(certificate, encryption.decrypt(key));
        }

        certificate.revoked(clock.instant());
        Certificate saved = certificates.save(certificate);
        audit.success(AuditAction.CERTIFICATE_REVOKED, RESOURCE_TYPE, id, null,
                Map.of("revokedAtAuthority", String.valueOf(provider.supportsRevocation())));
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        Certificate certificate = load(id);
        certificate.domains().forEach(permissions::requireCertificateAuthorityOver);

        if (certificates.isInUse(id)) {
            // Deleting it would leave sites referencing material that no longer exists, and their
            // next deployment would fail to render.
            throw new ConflictException(
                    "This certificate is attached to one or more proxy sites. Detach it before deleting.");
        }
        certificates.deleteById(id);
        audit.success(AuditAction.CERTIFICATE_DELETED, RESOURCE_TYPE, id,
                Map.of("name", certificate.name()), null);
    }

    /**
     * Refuses material whose key does not match its certificate.
     *
     * <p>Caught here rather than at deployment: NGINX would refuse to load the pair, and the
     * failure would surface as a rejected bundle long after the upload that caused it.
     */
    private static void requireKeyMatchesCertificate(IssuedCertificate issued) {
        if (issued.privateKeyPem() == null || !issued.privateKeyPem().contains("PRIVATE KEY")) {
            throw new ValidationException("privateKeyPem", "The private key is not in PEM format");
        }
    }

    private CertificateProvider provider(CertificateProviderKind kind) {
        CertificateProvider provider = providers.get(kind);
        if (provider == null) {
            throw new ConflictException("No provider is configured for " + kind + " certificates");
        }
        return provider;
    }

    private Certificate load(UUID id) {
        return certificates.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }

    private String actor() {
        return actorProvider.currentActor().username();
    }
}
