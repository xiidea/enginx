package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.proxy.HeaderDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "proxy_site_headers")
public class ProxySiteHeaderEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private HeaderDirection direction;

    @Column(name = "header_name", nullable = false, length = 128)
    private String headerName;

    @Column(name = "header_value", nullable = false, length = 1024)
    private String headerValue;

    protected ProxySiteHeaderEntity() {
    }

    public ProxySiteHeaderEntity(UUID id, HeaderDirection direction, String headerName, String headerValue) {
        this.id = id;
        this.direction = direction;
        this.headerName = headerName;
        this.headerValue = headerValue;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_header} constraint; header names are case-insensitive. */
    public String naturalKey() {
        return naturalKey(direction, headerName);
    }

    public static String naturalKey(HeaderDirection direction, String headerName) {
        return direction + "|" + headerName.toLowerCase(java.util.Locale.ROOT);
    }

    public void update(String headerValue) {
        this.headerValue = headerValue;
    }

    public HeaderDirection getDirection() {
        return direction;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }
}
