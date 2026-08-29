package net.xiidea.enginx.infrastructure.audit;

import net.xiidea.enginx.application.audit.AuditPartitionManager;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Calls the partition function created with the audit schema.
 *
 * <p>The logic lives in the database because creating a partition is a DDL statement built from a
 * date, and doing that from application code means assembling SQL strings — the one place this
 * system deliberately never does that.
 */
@Component
public class SqlAuditPartitionManager implements AuditPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(SqlAuditPartitionManager.class);

    private final EntityManager entityManager;

    public SqlAuditPartitionManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public int ensureAhead(int monthsAhead) {
        Object result = entityManager
                .createNativeQuery("select enginx_ensure_audit_partitions(:months)")
                .setParameter("months", monthsAhead)
                .getSingleResult();

        int ensured = result instanceof Number number ? number.intValue() : 0;
        log.debug("Ensured {} audit partition(s) exist", ensured);
        return ensured;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> dropPartitionsBefore(LocalDate cutoff) {
        List<Object> rows = entityManager
                .createNativeQuery("select enginx_drop_audit_partitions_before(:cutoff)")
                .setParameter("cutoff", cutoff)
                .getResultList();

        List<String> dropped = rows.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList();

        if (!dropped.isEmpty()) {
            log.warn("Dropped audit partitions ending on or before {}: {}", cutoff, dropped);
        }
        return dropped;
    }
}
