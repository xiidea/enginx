package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.CertificateSecretEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CertificateSecretJpaRepository extends JpaRepository<CertificateSecretEntity, UUID> {
}
