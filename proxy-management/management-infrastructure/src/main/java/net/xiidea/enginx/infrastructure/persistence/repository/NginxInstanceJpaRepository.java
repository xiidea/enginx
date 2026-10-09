package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.NginxInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NginxInstanceJpaRepository extends JpaRepository<NginxInstanceEntity, UUID> {

    boolean existsByName(String name);

    /** Hosts whose token is sealed, and so may need moving to a new key. */
    List<NginxInstanceEntity> findByAgentTokenCiphertextIsNotNull();
}
