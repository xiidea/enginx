package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.deployment.DeploymentEvent;
import net.xiidea.enginx.domain.deployment.DeploymentPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_events")
public class DeploymentEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deployment_id", nullable = false)
    private DeploymentEntity deployment;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 16)
    private DeploymentPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private DeploymentEvent.EventResult result;

    @Column(name = "detail")
    private String detail;

    @Column(name = "at", nullable = false)
    private Instant at;

    protected DeploymentEventEntity() {
    }

    public DeploymentEventEntity(UUID id, DeploymentPhase phase, DeploymentEvent.EventResult result,
                                 String detail, Instant at) {
        this.id = id;
        this.phase = phase;
        this.result = result;
        this.detail = detail;
        this.at = at;
    }

    void setDeployment(DeploymentEntity deployment) {
        this.deployment = deployment;
    }

    public UUID getId() {
        return id;
    }

    public DeploymentPhase getPhase() {
        return phase;
    }

    public DeploymentEvent.EventResult getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
