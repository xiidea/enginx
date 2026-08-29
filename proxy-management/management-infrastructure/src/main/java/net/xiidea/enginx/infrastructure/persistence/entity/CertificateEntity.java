package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.certificate.CertificateProviderKind;
import net.xiidea.enginx.domain.certificate.CertificateStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Public certificate material and metadata. The private key lives in its own table so that the
 * common read path never loads it.
 */
@Entity
@Table(name = "certificates")
public class CertificateEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private CertificateProviderKind provider;

    @Column(name = "issuer", length = 256)
    private String issuer;

    @Column(name = "subject", length = 256)
    private String subject;

    @Column(name = "serial_number", length = 128)
    private String serialNumber;

    @Column(name = "fingerprint_sha256", length = 64)
    private String fingerprintSha256;

    /** The full chain, leaf first, as NGINX requires. */
    @Column(name = "certificate_pem")
    private String certificatePem;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CertificateStatus status;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "renew_before_days", nullable = false)
    private int renewBeforeDays;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "certificate", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<CertificateDomainEntity> domains = new ArrayList<>();

    protected CertificateEntity() {
    }

    public CertificateEntity(UUID id) {
        this.id = id;
    }

    public void attach(CertificateDomainEntity domain) {
        domain.setCertificate(this);
        domains.add(domain);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CertificateProviderKind getProvider() {
        return provider;
    }

    public void setProvider(CertificateProviderKind provider) {
        this.provider = provider;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getFingerprintSha256() {
        return fingerprintSha256;
    }

    public void setFingerprintSha256(String fingerprintSha256) {
        this.fingerprintSha256 = fingerprintSha256;
    }

    public String getCertificatePem() {
        return certificatePem;
    }

    public void setCertificatePem(String certificatePem) {
        this.certificatePem = certificatePem;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public void setStatus(CertificateStatus status) {
        this.status = status;
    }

    public boolean isAutoRenew() {
        return autoRenew;
    }

    public void setAutoRenew(boolean autoRenew) {
        this.autoRenew = autoRenew;
    }

    public int getRenewBeforeDays() {
        return renewBeforeDays;
    }

    public void setRenewBeforeDays(int renewBeforeDays) {
        this.renewBeforeDays = renewBeforeDays;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<CertificateDomainEntity> getDomains() {
        return domains;
    }
}
