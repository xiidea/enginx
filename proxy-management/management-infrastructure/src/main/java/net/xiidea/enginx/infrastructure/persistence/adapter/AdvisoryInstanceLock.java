package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.application.deployment.InstanceLock;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * A PostgreSQL transaction-scoped advisory lock, keyed by instance id.
 *
 * <p>Advisory rather than a row lock because there is no single row that represents "deploying to
 * this host": the operation spans sites, bundles and deployments. {@code pg_advisory_xact_lock}
 * takes a lock on an arbitrary key and releases it when the transaction ends, including on
 * rollback or a lost connection, so a worker that dies mid-deployment cannot wedge the instance.
 */
@Component
public class AdvisoryInstanceLock implements InstanceLock {

    private final EntityManager entityManager;

    public AdvisoryInstanceLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void lockForUpdate(UUID nginxInstanceId) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:key)")
                .setParameter("key", keyFor(nginxInstanceId))
                .getSingleResult();
    }

    /**
     * Folds the UUID into the 64-bit key the advisory lock functions take.
     *
     * <p>A collision would mean two unrelated instances serialising against each other: slower,
     * never incorrect. That is the right trade for a lock whose alternative is a bespoke table.
     */
    private static long keyFor(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        buffer.flip();
        return buffer.getLong() ^ buffer.getLong();
    }
}
