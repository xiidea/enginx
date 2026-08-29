package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "certificate_domains")
public class CertificateDomainEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "certificate_id", nullable = false)
    private CertificateEntity certificate;

    @Column(name = "domain", nullable = false, length = 255)
    private String domain;

    @Column(name = "wildcard", nullable = false)
    private boolean wildcard;

    protected CertificateDomainEntity() {
    }

    public CertificateDomainEntity(UUID id, String domain, boolean wildcard) {
        this.id = id;
        this.domain = domain;
        this.wildcard = wildcard;
    }

    void setCertificate(CertificateEntity certificate) {
        this.certificate = certificate;
    }

    public String getDomain() {
        return domain;
    }

    public boolean isWildcard() {
        return wildcard;
    }
}
