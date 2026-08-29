package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.xiidea.enginx.domain.agent.AgentJobStatus;
import net.xiidea.enginx.domain.agent.AgentJobType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_jobs")
public class AgentJobEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "nginx_instance_id", nullable = false)
    private UUID nginxInstanceId;

    @Column(name = "deployment_id")
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private AgentJobType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AgentJobStatus status;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private String result;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentJobEntity() {
    }

    public AgentJobEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNginxInstanceId() {
        return nginxInstanceId;
    }

    public void setNginxInstanceId(UUID nginxInstanceId) {
        this.nginxInstanceId = nginxInstanceId;
    }

    public UUID getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(UUID deploymentId) {
        this.deploymentId = deploymentId;
    }

    public AgentJobType getType() {
        return type;
    }

    public void setType(AgentJobType type) {
        this.type = type;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public AgentJobStatus getStatus() {
        return status;
    }

    public void setStatus(AgentJobStatus status) {
        this.status = status;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
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
}
