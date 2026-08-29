package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.LocalUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LocalUserJpaRepository extends JpaRepository<LocalUserEntity, UUID> {

    Optional<LocalUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
