package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Deliberately not a {@code JpaRepository}: extending the full interface would hand the
 * application {@code delete} and {@code saveAll} methods that must never exist for audit rows.
 * Only insertion is exposed.
 */
public interface AuditLogJpaRepository extends Repository<AuditLogEntity, UUID> {

    AuditLogEntity save(AuditLogEntity event);
}
