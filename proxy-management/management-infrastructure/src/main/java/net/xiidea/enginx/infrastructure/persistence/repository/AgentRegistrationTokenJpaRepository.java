package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AgentRegistrationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentRegistrationTokenJpaRepository extends JpaRepository<AgentRegistrationTokenEntity, UUID> {

    Optional<AgentRegistrationTokenEntity> findByTokenHash(String tokenHash);
}
