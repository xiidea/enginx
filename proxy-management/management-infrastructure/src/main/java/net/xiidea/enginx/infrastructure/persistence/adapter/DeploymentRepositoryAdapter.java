package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentEvent;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.infrastructure.persistence.entity.DeploymentEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.DeploymentEventEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.DeploymentJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DeploymentRepositoryAdapter implements DeploymentRepository {

    private final DeploymentJpaRepository repository;

    public DeploymentRepositoryAdapter(DeploymentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Deployment save(Deployment deployment) {
        DeploymentEntity entity = repository.findById(deployment.id())
                .orElseGet(() -> new DeploymentEntity(deployment.id()));

        entity.setNginxInstanceId(deployment.nginxInstanceId());
        entity.setConfigBundleId(deployment.configBundleId());
        entity.setPreviousBundleId(deployment.previousBundleId());
        entity.setTriggerType(deployment.trigger());
        entity.setStatus(deployment.status());
        entity.setAttempt(deployment.attempt());
        entity.setIdempotencyKey(deployment.idempotencyKey());
        entity.setNginxTestOutput(deployment.nginxTestOutput());
        entity.setErrorMessage(deployment.errorMessage());
        entity.setStartedAt(deployment.startedAt());
        entity.setFinishedAt(deployment.finishedAt());
        entity.setCreatedBy(deployment.createdBy());
        entity.setCreatedAt(deployment.createdAt());

        // Events are append-only, so only the ones not already stored are attached. Replacing
        // the collection would rewrite the phase history on every save.
        Set<UUID> existing = entity.getEvents().stream()
                .map(DeploymentEventEntity::getId)
                .collect(Collectors.toSet());
        for (DeploymentEvent event : deployment.events()) {
            if (existing.add(event.id())) {
                entity.attach(new DeploymentEventEntity(event.id(), event.phase(), event.result(),
                        event.detail(), event.at()));
            }
        }

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Deployment> findById(UUID id) {
        return repository.findByIdWithEvents(id).map(DeploymentRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Deployment> search(UUID nginxInstanceId, List<DeploymentStatus> statuses, int page, int size) {
        Page<DeploymentEntity> result = repository.search(nginxInstanceId, statuses,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        return new PageResult<>(result.getContent().stream().map(DeploymentRepositoryAdapter::toDomain).toList(),
                page, size, result.getTotalElements());
    }

    @Override
    public Optional<Deployment> findLatestForInstance(UUID nginxInstanceId) {
        return repository.findFirstByNginxInstanceIdOrderByCreatedAtDesc(nginxInstanceId)
                .map(DeploymentRepositoryAdapter::toDomain);
    }

    @Override
    public boolean hasActiveDeployment(UUID nginxInstanceId) {
        return repository.existsByNginxInstanceIdAndStatusIn(nginxInstanceId,
                List.of(DeploymentStatus.PENDING, DeploymentStatus.IN_PROGRESS));
    }

    private static Deployment toDomain(DeploymentEntity entity) {
        List<DeploymentEvent> events = entity.getEvents().stream()
                .map(event -> new DeploymentEvent(event.getId(), entity.getId(), event.getPhase(),
                        event.getResult(), event.getDetail(), event.getAt()))
                .toList();

        return Deployment.rehydrate(entity.getId(), entity.getNginxInstanceId(), entity.getConfigBundleId(),
                entity.getPreviousBundleId(), entity.getTriggerType(), entity.getIdempotencyKey(),
                entity.getStatus(), entity.getAttempt(), entity.getNginxTestOutput(), entity.getErrorMessage(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getCreatedBy(), entity.getCreatedAt(),
                events);
    }
}
