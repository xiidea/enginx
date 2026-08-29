package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobPayload;
import net.xiidea.enginx.domain.agent.AgentJobRepository;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.agent.AgentJobStatus;
import tools.jackson.databind.ObjectMapper;
import net.xiidea.enginx.infrastructure.persistence.entity.AgentJobEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AgentJobJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class AgentJobRepositoryAdapter implements AgentJobRepository {

    private final AgentJobJpaRepository repository;

    /**
     * Serialises the payload and result to the jsonb columns.
     *
     * <p>Here rather than in the application module, which deliberately has no JSON library: what
     * a job means is a domain concern, how it is stored is not.
     */
    private final ObjectMapper json;

    public AgentJobRepositoryAdapter(AgentJobJpaRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Override
    public AgentJob save(AgentJob job) {
        AgentJobEntity entity = repository.findById(job.id()).orElseGet(() -> new AgentJobEntity(job.id()));
        entity.setNginxInstanceId(job.nginxInstanceId());
        entity.setDeploymentId(job.deploymentId());
        entity.setType(job.type());
        entity.setPayload(json.writeValueAsString(job.payload()));
        entity.setStatus(job.status());
        entity.setLeaseExpiresAt(job.leaseExpiresAt());
        entity.setAttempts(job.attempts());
        entity.setResult(job.result() == null ? null : json.writeValueAsString(job.result()));
        entity.setError(job.error());
        entity.setCreatedAt(job.createdAt());
        entity.setUpdatedAt(job.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<AgentJob> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<AgentJob> findNextClaimable(UUID nginxInstanceId, Instant now) {
        return repository.findClaimable(nginxInstanceId, now, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(this::toDomain);
    }

    @Override
    public List<AgentJob> findExpiredLeases(Instant now, int limit) {
        return repository.findExpiredLeases(now, PageRequest.of(0, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<AgentJob> findPendingForDeployment(UUID deploymentId) {
        return repository.findByDeploymentIdAndStatusIn(deploymentId,
                        List.of(AgentJobStatus.QUEUED, AgentJobStatus.LEASED)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<AgentJob> findRecentForInstance(UUID nginxInstanceId, int limit) {
        return repository.findByNginxInstanceIdOrderByCreatedAtDesc(nginxInstanceId,
                        PageRequest.of(0, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    private AgentJob toDomain(AgentJobEntity entity) {
        return AgentJob.rehydrate(entity.getId(), entity.getNginxInstanceId(), entity.getDeploymentId(),
                entity.getType(), json.readValue(entity.getPayload(), AgentJobPayload.class),
                entity.getStatus(), entity.getLeaseExpiresAt(), entity.getAttempts(),
                entity.getResult() == null ? null : json.readValue(entity.getResult(), AgentJobResult.class),
                entity.getError(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
