package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Read access to the audit trail.
 *
 * <p>Extends the bare {@code Repository} marker rather than {@code JpaRepository}, so the only
 * methods that exist are the ones declared here. Inheriting the full interface would hand callers
 * {@code delete}, {@code save} and {@code deleteAll} on a table that must never be modified.
 */
public interface AuditLogQueryRepository
        extends Repository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {

    @Override
    Page<AuditLogEntity> findAll(org.springframework.data.jpa.domain.Specification<AuditLogEntity> specification,
                                 Pageable pageable);
}
