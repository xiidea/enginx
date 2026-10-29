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
@Table(name = "proxy_site_oidc_roles")
public class ProxySiteOidcRoleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    /** Null for a realm role. */
    @Column(name = "client_id", length = 128)
    private String clientId;

    @Column(name = "role_name", nullable = false, length = 128)
    private String roleName;

    protected ProxySiteOidcRoleEntity() {
    }

    public ProxySiteOidcRoleEntity(UUID id, String clientId, String roleName) {
        this.id = id;
        this.clientId = clientId;
        this.roleName = roleName;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_oidc_role} index. */
    public String naturalKey() {
        return naturalKey(clientId, roleName);
    }

    public static String naturalKey(String clientId, String roleName) {
        return (clientId == null ? "" : clientId) + ":" + roleName;
    }

    public String getClientId() {
        return clientId;
    }

    public String getRoleName() {
        return roleName;
    }
}
