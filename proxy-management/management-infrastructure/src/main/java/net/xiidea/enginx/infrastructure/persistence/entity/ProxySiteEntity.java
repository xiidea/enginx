package net.xiidea.enginx.infrastructure.persistence.entity;

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

@Entity
@Table(name = "proxy_sites")
public class ProxySiteEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "domain", nullable = false, length = 253)
    private String domain;

    /** Dot-reversed domain, indexed for the wildcard permission matching added in Phase 3. */
    @Column(name = "domain_reversed", nullable = false, length = 253)
    private String domainReversed;

    @Column(name = "nginx_instance_id", nullable = false)
    private UUID nginxInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_state", nullable = false, length = 16)
    private net.xiidea.enginx.domain.proxy.AdminState adminState;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private net.xiidea.enginx.domain.proxy.SiteStatus status;

    @Column(name = "active_from")
    private Instant activeFrom;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "ssl_enabled", nullable = false)
    private boolean sslEnabled;

    @Column(name = "force_https", nullable = false)
    private boolean forceHttps;

    @Column(name = "hsts_enabled", nullable = false)
    private boolean hstsEnabled;

    @Column(name = "websocket_enabled", nullable = false)
    private boolean websocketEnabled;

    @Column(name = "ssl_certificate_id")
    private UUID sslCertificateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lb_method", nullable = false, length = 24)
    private net.xiidea.enginx.domain.proxy.LoadBalancingMethod lbMethod;

    @Column(name = "connect_timeout_s", nullable = false)
    private int connectTimeoutSeconds;

    @Column(name = "read_timeout_s", nullable = false)
    private int readTimeoutSeconds;

    @Column(name = "send_timeout_s", nullable = false)
    private int sendTimeoutSeconds;

    @Column(name = "max_body_size_bytes", nullable = false)
    private long maxBodySizeBytes;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false, length = 128)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ProxySiteUpstreamEntity> upstreams = new ArrayList<>();

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ProxySiteHeaderEntity> headers = new ArrayList<>();

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ProxySiteLocationEntity> locations = new ArrayList<>();

    protected ProxySiteEntity() {
    }

    public ProxySiteEntity(UUID id) {
        this.id = id;
    }

    /**
     * The live child collections. The mapper merges into these in place: clearing them would
     * make Hibernate delete every row and insert a replacement with a fresh id, and because
     * inserts are ordered before deletes within a flush, re-inserting a row with the same
     * natural key violates its unique constraint against the row it is replacing.
     */
    public List<ProxySiteUpstreamEntity> upstreams() {
        return upstreams;
    }

    public List<ProxySiteHeaderEntity> headers() {
        return headers;
    }

    public List<ProxySiteLocationEntity> locations() {
        return locations;
    }

    public void attach(ProxySiteUpstreamEntity child) {
        child.setSite(this);
    }

    public void attach(ProxySiteHeaderEntity child) {
        child.setSite(this);
    }

    public void attach(ProxySiteLocationEntity child) {
        child.setSite(this);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getDomainReversed() {
        return domainReversed;
    }

    public void setDomainReversed(String domainReversed) {
        this.domainReversed = domainReversed;
    }

    public UUID getNginxInstanceId() {
        return nginxInstanceId;
    }

    public void setNginxInstanceId(UUID nginxInstanceId) {
        this.nginxInstanceId = nginxInstanceId;
    }

    public net.xiidea.enginx.domain.proxy.AdminState getAdminState() {
        return adminState;
    }

    public void setAdminState(net.xiidea.enginx.domain.proxy.AdminState adminState) {
        this.adminState = adminState;
    }

    public net.xiidea.enginx.domain.proxy.SiteStatus getStatus() {
        return status;
    }

    public void setStatus(net.xiidea.enginx.domain.proxy.SiteStatus status) {
        this.status = status;
    }

    public Instant getActiveFrom() {
        return activeFrom;
    }

    public void setActiveFrom(Instant activeFrom) {
        this.activeFrom = activeFrom;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public boolean isForceHttps() {
        return forceHttps;
    }

    public void setForceHttps(boolean forceHttps) {
        this.forceHttps = forceHttps;
    }

    public boolean isHstsEnabled() {
        return hstsEnabled;
    }

    public void setHstsEnabled(boolean hstsEnabled) {
        this.hstsEnabled = hstsEnabled;
    }

    public boolean isWebsocketEnabled() {
        return websocketEnabled;
    }

    public void setWebsocketEnabled(boolean websocketEnabled) {
        this.websocketEnabled = websocketEnabled;
    }

    public UUID getSslCertificateId() {
        return sslCertificateId;
    }

    public void setSslCertificateId(UUID sslCertificateId) {
        this.sslCertificateId = sslCertificateId;
    }

    public net.xiidea.enginx.domain.proxy.LoadBalancingMethod getLbMethod() {
        return lbMethod;
    }

    public void setLbMethod(net.xiidea.enginx.domain.proxy.LoadBalancingMethod lbMethod) {
        this.lbMethod = lbMethod;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getSendTimeoutSeconds() {
        return sendTimeoutSeconds;
    }

    public void setSendTimeoutSeconds(int sendTimeoutSeconds) {
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    public long getMaxBodySizeBytes() {
        return maxBodySizeBytes;
    }

    public void setMaxBodySizeBytes(long maxBodySizeBytes) {
        this.maxBodySizeBytes = maxBodySizeBytes;
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

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
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

}
