package net.xiidea.enginx.application.deployment;

import java.util.UUID;

/**
 * A cluster-wide lock on one NGINX instance, held for the duration of a transaction.
 *
 * <p>Rendering reads the current state of every site on a host and then writes what it computed.
 * Two dispatchers doing that at once would interleave: the second could render before the first's
 * result was visible, and then activate a bundle that silently omits the first's change
 * (architecture risk R2).
 */
public interface InstanceLock {

    /**
     * Blocks until this instance is free, then holds the lock until the surrounding transaction
     * ends. Must be called inside a transaction.
     */
    void lockForUpdate(UUID nginxInstanceId);
}
