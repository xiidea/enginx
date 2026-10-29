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
@Table(name = "proxy_site_oidc_users")
public class ProxySiteOidcUserEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    @Column(name = "username", nullable = false, length = 254)
    private String username;

    protected ProxySiteOidcUserEntity() {
    }

    public ProxySiteOidcUserEntity(UUID id, String username) {
        this.id = id;
        this.username = username;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_oidc_user} constraint. */
    public String naturalKey() {
        return username;
    }

    public String getUsername() {
        return username;
    }
}
