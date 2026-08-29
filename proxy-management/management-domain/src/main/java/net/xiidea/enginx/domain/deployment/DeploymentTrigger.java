package net.xiidea.enginx.domain.deployment;

public enum DeploymentTrigger {
    MANUAL,
    /** A site's expiry passed and its configuration must leave the bundle. */
    EXPIRATION,
    /** A site's activation window opened. */
    ACTIVATION,
    CERT_RENEWAL,
    /** Re-activating a previously validated bundle. Never automatic. */
    ROLLBACK,
    /** The host drifted from what the database believes it is serving. */
    RECONCILE
}
