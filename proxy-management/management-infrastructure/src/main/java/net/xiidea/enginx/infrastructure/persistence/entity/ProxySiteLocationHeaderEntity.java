package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/** A request header one location adds to its site's. */
@Entity
@Table(name = "proxy_site_location_headers")
public class ProxySiteLocationHeaderEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private ProxySiteLocationEntity location;

    @Column(name = "header_name", nullable = false, length = 128)
    private String headerName;

    @Column(name = "header_value", nullable = false, length = 1024)
    private String headerValue;

    protected ProxySiteLocationHeaderEntity() {
    }

    public ProxySiteLocationHeaderEntity(UUID id, String headerName, String headerValue) {
        this.id = id;
        this.headerName = headerName;
        this.headerValue = headerValue;
    }

    void setLocation(ProxySiteLocationEntity location) {
        this.location = location;
    }

    /** Matches the {@code uq_location_header} constraint. */
    public String naturalKey() {
        return headerName;
    }

    public void update(String headerValue) {
        this.headerValue = headerValue;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }
}
