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
@Table(name = "proxy_site_allowed_emails")
public class ProxySiteAllowedEmailEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    protected ProxySiteAllowedEmailEntity() {
    }

    public ProxySiteAllowedEmailEntity(UUID id, String email) {
        this.id = id;
        this.email = email;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_allowed_email} constraint. */
    public String naturalKey() {
        return email;
    }

    public String getEmail() {
        return email;
    }
}
