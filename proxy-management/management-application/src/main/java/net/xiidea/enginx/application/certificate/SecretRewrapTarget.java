package net.xiidea.enginx.application.certificate;

/**
 * A store of encrypted secrets that can be moved to the current key.
 *
 * <p>Exists because certificate private keys are not the only thing wrapped. The ACME account key
 * is too, and a migration that re-wrapped only certificates would leave the old key required
 * forever — by one row, invisibly, until someone removed the key and issuance stopped working.
 *
 * <p>Implementations are collected, so a future store gets migrated by being registered rather
 * than by someone remembering to extend a method.
 */
public interface SecretRewrapTarget {

    /** What this store holds, for the audit record and the operator's report. */
    String name();

    /**
     * Re-wraps everything not already under {@code currentKeyId}.
     *
     * @return how many secrets were moved
     */
    int rewrap(String currentKeyId);
}
