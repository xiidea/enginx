package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.certificate.Certificate;
import net.xiidea.enginx.domain.certificate.CertificateMetadata;
import net.xiidea.enginx.domain.certificate.CertificateRepository;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.infrastructure.persistence.entity.CertificateDomainEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.CertificateEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.CertificateSecretEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.CertificateJpaRepository;
import net.xiidea.enginx.infrastructure.persistence.repository.CertificateSecretJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class CertificateRepositoryAdapter implements CertificateRepository {

    /**
     * The widest renewal window any certificate may configure. Used to bound the renewal query so
     * it stays a range scan rather than a full read; the aggregate then decides precisely.
     */
    private static final Duration MAX_RENEW_WINDOW = Duration.ofDays(89);

    private final CertificateJpaRepository certificates;
    private final CertificateSecretJpaRepository secrets;

    public CertificateRepositoryAdapter(CertificateJpaRepository certificates,
                                        CertificateSecretJpaRepository secrets) {
        this.certificates = certificates;
        this.secrets = secrets;
    }

    @Override
    @Transactional
    public Certificate save(Certificate certificate) {
        CertificateEntity entity = certificates.findByIdWithDomains(certificate.id())
                .orElseGet(() -> new CertificateEntity(certificate.id()));

        entity.setName(certificate.name());
        entity.setProvider(certificate.provider());
        entity.setStatus(certificate.status());
        entity.setAutoRenew(certificate.autoRenew());
        entity.setRenewBeforeDays(certificate.renewBeforeDays());
        entity.setLastError(truncate(certificate.lastError()));
        entity.setRevokedAt(certificate.revokedAt());
        entity.setCreatedBy(certificate.createdBy());
        entity.setCreatedAt(certificate.createdAt());
        entity.setUpdatedAt(certificate.updatedAt());
        entity.setCertificatePem(certificate.fullChainPem());

        CertificateMetadata metadata = certificate.metadata();
        if (metadata != null) {
            entity.setIssuer(metadata.issuer());
            entity.setSubject(metadata.subject());
            entity.setSerialNumber(metadata.serialNumber());
            entity.setFingerprintSha256(metadata.fingerprintSha256());
            entity.setNotBefore(metadata.notBefore());
            entity.setIssuedAt(metadata.notBefore());
            entity.setExpiresAt(metadata.notAfter());
        }

        mergeDomains(entity, certificate.domains());
        return toDomain(certificates.saveAndFlush(entity));
    }

    /**
     * Replaces the domain list only when it actually differs, so a status refresh does not churn
     * child rows on every sweep.
     */
    private static void mergeDomains(CertificateEntity entity, Set<String> desired) {
        Set<String> existing = new LinkedHashSet<>();
        entity.getDomains().forEach(domain -> existing.add(domain.getDomain()));
        if (existing.equals(desired)) {
            return;
        }
        entity.getDomains().clear();
        desired.forEach(domain ->
                entity.attach(new CertificateDomainEntity(UUID.randomUUID(), domain, domain.startsWith("*."))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Certificate> findById(UUID id) {
        return certificates.findByIdWithDomains(id).map(CertificateRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Certificate> findAll() {
        return certificates.findAllWithDomains().stream().map(CertificateRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Certificate> findDueForRenewal(Instant now, int limit) {
        return certificates.findDueForRenewal(now.plus(MAX_RENEW_WINDOW), Limit.of(limit)).stream()
                .map(CertificateRepositoryAdapter::toDomain)
                // The query is a coarse filter bounded by the widest possible window; the
                // aggregate applies each certificate's own setting.
                .filter(certificate -> certificate.needsRenewal(now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Certificate> findWithStaleStatus(Instant now, int limit) {
        return certificates.findWithStaleExpiry(now, Limit.of(limit)).stream()
                .map(CertificateRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInUse(UUID certificateId) {
        return certificates.isReferencedByAnySite(certificateId);
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        certificates.deleteById(id);
    }

    @Override
    @Transactional
    public void storePrivateKey(UUID certificateId, EncryptedSecret key) {
        Instant now = Instant.now();
        secrets.findById(certificateId).ifPresentOrElse(
                existing -> existing.replace(key.ciphertext(), key.wrappedDataKey(), key.kekId(),
                        key.cipher(), key.iv(), now),
                () -> secrets.save(new CertificateSecretEntity(certificateId, key.ciphertext(),
                        key.wrappedDataKey(), key.kekId(), key.cipher(), key.iv(), now)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EncryptedSecret> findPrivateKey(UUID certificateId) {
        return secrets.findById(certificateId).map(entity -> new EncryptedSecret(
                entity.getCiphertext(), entity.getWrappedDek(), entity.getKekId(),
                entity.getCipher(), entity.getIv(), new byte[0]));
    }

    private static Certificate toDomain(CertificateEntity entity) {
        Set<String> domains = new LinkedHashSet<>();
        entity.getDomains().forEach(domain -> domains.add(domain.getDomain()));

        CertificateMetadata metadata = entity.getCertificatePem() == null ? null : new CertificateMetadata(
                entity.getSubject(), entity.getIssuer(), entity.getSerialNumber(),
                entity.getFingerprintSha256(), entity.getNotBefore(), entity.getExpiresAt(), domains);

        return Certificate.rehydrate(entity.getId(), entity.getProvider(), entity.getName(), domains,
                entity.getCertificatePem(), metadata, entity.getStatus(), entity.isAutoRenew(),
                entity.getRenewBeforeDays(), entity.getLastError(), entity.getRevokedAt(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getVersion());
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 4000) {
            return value;
        }
        return value.substring(0, 4000) + "… (truncated)";
    }
}
