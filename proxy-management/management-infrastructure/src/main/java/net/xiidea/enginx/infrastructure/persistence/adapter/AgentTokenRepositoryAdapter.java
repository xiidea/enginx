package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.agent.AgentToken;
import net.xiidea.enginx.domain.agent.AgentTokenRepository;
import net.xiidea.enginx.infrastructure.persistence.entity.AgentTokenEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.AgentTokenJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class AgentTokenRepositoryAdapter implements AgentTokenRepository {

    private final AgentTokenJpaRepository repository;

    public AgentTokenRepositoryAdapter(AgentTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AgentToken save(AgentToken token) {
        AgentTokenEntity entity = repository.findById(token.id())
                .orElseGet(() -> new AgentTokenEntity(token.id()));
        entity.setNginxInstanceId(token.nginxInstanceId());
        entity.setTokenHash(token.tokenHash());
        entity.setIssuedAt(token.issuedAt());
        entity.setLastUsedAt(token.lastUsedAt());
        entity.setRevokedAt(token.revokedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<AgentToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(AgentTokenRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<AgentToken> findActiveByInstanceId(UUID nginxInstanceId) {
        return repository.findByNginxInstanceIdAndRevokedAtIsNull(nginxInstanceId)
                .map(AgentTokenRepositoryAdapter::toDomain);
    }

    private static AgentToken toDomain(AgentTokenEntity entity) {
        return AgentToken.rehydrate(entity.getId(), entity.getNginxInstanceId(), entity.getTokenHash(),
                entity.getIssuedAt(), entity.getLastUsedAt(), entity.getRevokedAt());
    }
}
