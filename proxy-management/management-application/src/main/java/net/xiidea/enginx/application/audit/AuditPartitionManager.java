package net.xiidea.enginx.application.audit;

import java.time.LocalDate;
import java.util.List;

/**
 * Creates audit partitions ahead of time.
 *
 * <p>A port, because the mechanism is entirely PostgreSQL's — declarative partitioning has no
 * portable expression — and the application layer should not contain SQL.
 */
public interface AuditPartitionManager {


    /** @return how many partitions were ensured to exist */
    int ensureAhead(int monthsAhead);

    /**
     * Drops every monthly partition that ends on or before {@code cutoff}.
     *
     * <p>Never touches the default partition: rows land there only when their timestamp falls
     * outside every monthly range, which is a symptom worth keeping rather than discarding.
     *
     * @return the names of the partitions dropped, for the audit record of the deletion
     */
    List<String> dropPartitionsBefore(LocalDate cutoff);
}
