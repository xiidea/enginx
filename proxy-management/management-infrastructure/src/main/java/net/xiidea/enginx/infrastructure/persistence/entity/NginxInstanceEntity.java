package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.nginx.InstanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "nginx_instances")
public class NginxInstanceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "hostname", nullable = false, length = 253)
    private String hostname;

    @Column(name = "agent_base_url", nullable = false, length = 512)
    private String agentBaseUrl;

    @Column(name = "agent_cert_fingerprint", nullable = false, length = 64)
    private String agentCertFingerprint;

    @Column(name = "environment", nullable = false, length = 32)
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InstanceStatus status;

    @Column(name = "nginx_version", length = 32)
    private String nginxVersion;

    @Column(name = "agent_version", length = 32)
    private String agentVersion;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NginxInstanceEntity() {
    }

    public NginxInstanceEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public String getAgentCertFingerprint() {
        return agentCertFingerprint;
    }

    public void setAgentCertFingerprint(String agentCertFingerprint) {
        this.agentCertFingerprint = agentCertFingerprint;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public InstanceStatus getStatus() {
        return status;
    }

    public void setStatus(InstanceStatus status) {
        this.status = status;
    }

    public String getNginxVersion() {
        return nginxVersion;
    }

    public void setNginxVersion(String nginxVersion) {
        this.nginxVersion = nginxVersion;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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
