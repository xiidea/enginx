package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AcmeAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AcmeAccountJpaRepository extends JpaRepository<AcmeAccountEntity, UUID> {

    Optional<AcmeAccountEntity> findByDirectoryUrl(String directoryUrl);
}
