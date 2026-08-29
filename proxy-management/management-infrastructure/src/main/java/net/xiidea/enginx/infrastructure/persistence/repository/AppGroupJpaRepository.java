package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AppGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppGroupJpaRepository extends JpaRepository<AppGroupEntity, UUID> {

    Optional<AppGroupEntity> findByKeycloakGroupPath(String keycloakGroupPath);

    List<AppGroupEntity> findAllByOrderByKeycloakGroupPathAsc();
}
