package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "proxy_site_locations")
public class ProxySiteLocationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    @Column(name = "path_pattern", nullable = false, length = 256)
    private String pathPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 16)
    private LocationMatchType matchType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "public_access", nullable = false)
    private boolean publicAccess;

    // Null columns below are the site's value.
    @Enumerated(EnumType.STRING)
    @Column(name = "load_balancing_method", length = 16)
    private LoadBalancingMethod loadBalancingMethod;

    @Column(name = "connect_timeout_s")
    private Integer connectTimeoutSeconds;

    @Column(name = "read_timeout_s")
    private Integer readTimeoutSeconds;

    @Column(name = "send_timeout_s")
    private Integer sendTimeoutSeconds;

    @Column(name = "max_body_size_bytes")
    private Long maxBodySizeBytes;

    @Column(name = "websocket_enabled")
    private Boolean websocketEnabled;

    @OneToMany(mappedBy = "location", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ProxySiteLocationUpstreamEntity> upstreams = new ArrayList<>();

    @OneToMany(mappedBy = "location", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ProxySiteLocationHeaderEntity> headers = new ArrayList<>();

    protected ProxySiteLocationEntity() {
    }

    public ProxySiteLocationEntity(UUID id, String pathPattern, LocationMatchType matchType, int sortOrder) {
        this.id = id;
        this.pathPattern = pathPattern;
        this.matchType = matchType;
        this.sortOrder = sortOrder;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_location} constraint. */
    public String naturalKey() {
        return naturalKey(matchType, pathPattern);
    }

    public static String naturalKey(LocationMatchType matchType, String pathPattern) {
        return matchType + "|" + pathPattern;
    }

    public void update(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void updateSettings(boolean publicAccess, LoadBalancingMethod loadBalancingMethod,
                               Integer connectTimeoutSeconds, Integer readTimeoutSeconds, Integer sendTimeoutSeconds,
                               Long maxBodySizeBytes, Boolean websocketEnabled) {
        this.publicAccess = publicAccess;
        this.loadBalancingMethod = loadBalancingMethod;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.maxBodySizeBytes = maxBodySizeBytes;
        this.websocketEnabled = websocketEnabled;
    }

    public void attach(ProxySiteLocationUpstreamEntity upstream) {
        upstream.setLocation(this);
    }

    public void attach(ProxySiteLocationHeaderEntity header) {
        header.setLocation(this);
    }

    /** Mutated in place, never reassigned, for the same reason as the site's collections. */
    public List<ProxySiteLocationUpstreamEntity> upstreams() {
        return upstreams;
    }

    public List<ProxySiteLocationHeaderEntity> headers() {
        return headers;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public LocationMatchType getMatchType() {
        return matchType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isPublicAccess() {
        return publicAccess;
    }

    public LoadBalancingMethod getLoadBalancingMethod() {
        return loadBalancingMethod;
    }

    public Integer getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public Integer getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public Integer getSendTimeoutSeconds() {
        return sendTimeoutSeconds;
    }

    public Long getMaxBodySizeBytes() {
        return maxBodySizeBytes;
    }

    public Boolean getWebsocketEnabled() {
        return websocketEnabled;
    }
}
