package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.deployment.DeploymentTrigger;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "deployments")
public class DeploymentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "nginx_instance_id", nullable = false)
    private UUID nginxInstanceId;

    @Column(name = "config_bundle_id", nullable = false)
    private UUID configBundleId;

    @Column(name = "previous_bundle_id")
    private UUID previousBundleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private DeploymentTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeploymentStatus status;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "nginx_test_output")
    private String nginxTestOutput;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "deployment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("at asc")
    @BatchSize(size = 64)
    private List<DeploymentEventEntity> events = new ArrayList<>();

    protected DeploymentEntity() {
    }

    public DeploymentEntity(UUID id) {
        this.id = id;
    }

    public void attach(DeploymentEventEntity event) {
        event.setDeployment(this);
        events.add(event);
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

    public UUID getConfigBundleId() {
        return configBundleId;
    }

    public void setConfigBundleId(UUID configBundleId) {
        this.configBundleId = configBundleId;
    }

    public UUID getPreviousBundleId() {
        return previousBundleId;
    }

    public void setPreviousBundleId(UUID previousBundleId) {
        this.previousBundleId = previousBundleId;
    }

    public DeploymentTrigger getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(DeploymentTrigger triggerType) {
        this.triggerType = triggerType;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public void setStatus(DeploymentStatus status) {
        this.status = status;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getNginxTestOutput() {
        return nginxTestOutput;
    }

    public void setNginxTestOutput(String nginxTestOutput) {
        this.nginxTestOutput = nginxTestOutput;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
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

    public List<DeploymentEventEntity> getEvents() {
        return events;
    }
}
