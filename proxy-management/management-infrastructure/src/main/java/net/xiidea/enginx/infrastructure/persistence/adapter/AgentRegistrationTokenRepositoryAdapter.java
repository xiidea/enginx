package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.agent.AgentRegistrationToken;
import net.xiidea.enginx.domain.agent.AgentRegistrationTokenRepository;
import net.xiidea.enginx.infrastructure.persistence.entity.AgentRegistrationTokenEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AgentRegistrationTokenJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class AgentRegistrationTokenRepositoryAdapter implements AgentRegistrationTokenRepository {

    private final AgentRegistrationTokenJpaRepository repository;

    public AgentRegistrationTokenRepositoryAdapter(AgentRegistrationTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AgentRegistrationToken save(AgentRegistrationToken token) {
        AgentRegistrationTokenEntity entity = repository.findById(token.id())
                .orElseGet(() -> new AgentRegistrationTokenEntity(token.id()));
        entity.setTokenHash(token.tokenHash());
        entity.setDescription(token.description());
        entity.setExpiresAt(token.expiresAt());
        entity.setMaxUses(token.maxUses());
        entity.setUses(token.uses());
        entity.setRevokedAt(token.revokedAt());
        entity.setCreatedBy(token.createdBy());
        entity.setCreatedAt(token.createdAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<AgentRegistrationToken> findById(UUID id) {
        return repository.findById(id).map(AgentRegistrationTokenRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<AgentRegistrationToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(AgentRegistrationTokenRepositoryAdapter::toDomain);
    }

    @Override
    public List<AgentRegistrationToken> findAll() {
        return repository.findAll().stream()
                .map(AgentRegistrationTokenRepositoryAdapter::toDomain)
                .sorted(Comparator.comparing(AgentRegistrationToken::createdAt).reversed())
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    private static AgentRegistrationToken toDomain(AgentRegistrationTokenEntity entity) {
        return AgentRegistrationToken.rehydrate(entity.getId(), entity.getTokenHash(),
                entity.getDescription(), entity.getExpiresAt(), entity.getMaxUses(), entity.getUses(),
                entity.getRevokedAt(), entity.getCreatedBy(), entity.getCreatedAt());
    }
}
