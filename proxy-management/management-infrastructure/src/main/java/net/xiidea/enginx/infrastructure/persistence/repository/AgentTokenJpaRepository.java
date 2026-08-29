package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AgentTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentTokenJpaRepository extends JpaRepository<AgentTokenEntity, UUID> {

    Optional<AgentTokenEntity> findByTokenHash(String tokenHash);

    Optional<AgentTokenEntity> findByNginxInstanceIdAndRevokedAtIsNull(UUID nginxInstanceId);
}
