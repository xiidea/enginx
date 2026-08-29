package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserJpaRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByKeycloakSubject(String keycloakSubject);

    List<AppUserEntity> findAllByOrderByUsernameAsc();
}
