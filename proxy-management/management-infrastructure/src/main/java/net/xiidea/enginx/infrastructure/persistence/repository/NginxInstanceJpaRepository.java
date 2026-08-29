package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.NginxInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NginxInstanceJpaRepository extends JpaRepository<NginxInstanceEntity, UUID> {

    boolean existsByName(String name);
}
