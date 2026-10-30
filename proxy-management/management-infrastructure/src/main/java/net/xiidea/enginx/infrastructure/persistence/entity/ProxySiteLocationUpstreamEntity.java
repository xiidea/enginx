package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/** One server in a location's own upstream pool; the same shape as a site's. */
@Entity
@Table(name = "proxy_site_location_upstreams")
public class ProxySiteLocationUpstreamEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private ProxySiteLocationEntity location;

    @Column(name = "scheme", nullable = false, length = 8)
    private String scheme;

    @Column(name = "host", nullable = false, length = 253)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "weight", nullable = false)
    private int weight;

    @Column(name = "max_fails", nullable = false)
    private int maxFails;

    @Column(name = "fail_timeout_s", nullable = false)
    private int failTimeoutSeconds;

    @Column(name = "backup", nullable = false)
    private boolean backup;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ProxySiteLocationUpstreamEntity() {
    }

    public ProxySiteLocationUpstreamEntity(UUID id, String scheme, String host, int port, int weight,
                                           int maxFails, int failTimeoutSeconds, boolean backup, int sortOrder) {
        this.id = id;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.weight = weight;
        this.maxFails = maxFails;
        this.failTimeoutSeconds = failTimeoutSeconds;
        this.backup = backup;
        this.sortOrder = sortOrder;
    }

    void setLocation(ProxySiteLocationEntity location) {
        this.location = location;
    }

    /** Matches the {@code uq_location_upstream} constraint. */
    public String naturalKey() {
        return ProxySiteUpstreamEntity.naturalKey(scheme, host, port);
    }

    public void update(int weight, int maxFails, int failTimeoutSeconds, boolean backup, int sortOrder) {
        this.weight = weight;
        this.maxFails = maxFails;
        this.failTimeoutSeconds = failTimeoutSeconds;
        this.backup = backup;
        this.sortOrder = sortOrder;
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getWeight() {
        return weight;
    }

    public int getMaxFails() {
        return maxFails;
    }

    public int getFailTimeoutSeconds() {
        return failTimeoutSeconds;
    }

    public boolean isBackup() {
        return backup;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
