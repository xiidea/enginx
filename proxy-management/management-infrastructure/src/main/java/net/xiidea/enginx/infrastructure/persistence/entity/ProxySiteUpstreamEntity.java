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
@Table(name = "proxy_site_upstreams")
public class ProxySiteUpstreamEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

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

    protected ProxySiteUpstreamEntity() {
    }

    public ProxySiteUpstreamEntity(UUID id, String scheme, String host, int port, int weight,
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

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /**
     * Identity as the database sees it, matching the {@code uq_upstream} constraint. Rows are
     * matched on this when a site is saved so that an unchanged upstream keeps its row instead
     * of being deleted and re-inserted.
     */
    public String naturalKey() {
        return scheme + "|" + host + "|" + port;
    }

    public static String naturalKey(String scheme, String host, int port) {
        return scheme + "|" + host + "|" + port;
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
