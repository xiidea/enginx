package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.nginx.ConnectivityMode;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.PushTransport;
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

    // Nullable: only a push host is dialled, so only a push host has a URL or a pinned
    // certificate. A database check constraint holds the two field sets mutually exclusive.
    @Column(name = "agent_base_url", length = 512)
    private String agentBaseUrl;

    @Column(name = "agent_cert_fingerprint", length = 64)
    private String agentCertFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "connectivity_mode", nullable = false, length = 8)
    private ConnectivityMode connectivityMode = ConnectivityMode.PUSH;

    @Enumerated(EnumType.STRING)
    @Column(name = "push_transport", nullable = false, length = 16)
    private PushTransport pushTransport = PushTransport.MTLS;

    // The agent token, sealed under the same envelope scheme as private keys. All five set
    // together or all null; the schema checks the ciphertext, which cannot exist without the rest.
    @Column(name = "agent_token_ciphertext")
    private byte[] agentTokenCiphertext;

    @Column(name = "agent_token_wrapped_dek")
    private byte[] agentTokenWrappedDek;

    @Column(name = "agent_token_kek_id", length = 64)
    private String agentTokenKekId;

    @Column(name = "agent_token_cipher", length = 32)
    private String agentTokenCipher;

    @Column(name = "agent_token_iv")
    private byte[] agentTokenIv;

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

    public ConnectivityMode getConnectivityMode() {
        return connectivityMode;
    }

    public void setConnectivityMode(ConnectivityMode connectivityMode) {
        this.connectivityMode = connectivityMode;
    }

    public PushTransport getPushTransport() {
        return pushTransport;
    }

    public void setPushTransport(PushTransport pushTransport) {
        this.pushTransport = pushTransport;
    }

    public byte[] getAgentTokenCiphertext() {
        return agentTokenCiphertext;
    }

    public byte[] getAgentTokenWrappedDek() {
        return agentTokenWrappedDek;
    }

    public String getAgentTokenKekId() {
        return agentTokenKekId;
    }

    public String getAgentTokenCipher() {
        return agentTokenCipher;
    }

    public byte[] getAgentTokenIv() {
        return agentTokenIv;
    }

    /** Sets or clears all five sealed-token columns at once, so they cannot disagree. */
    public void sealAgentToken(byte[] ciphertext, byte[] wrappedDek, String kekId, String cipher, byte[] iv) {
        this.agentTokenCiphertext = ciphertext;
        this.agentTokenWrappedDek = wrappedDek;
        this.agentTokenKekId = kekId;
        this.agentTokenCipher = cipher;
        this.agentTokenIv = iv;
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
