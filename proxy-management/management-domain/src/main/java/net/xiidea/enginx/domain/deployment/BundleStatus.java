package net.xiidea.enginx.domain.deployment;

public enum BundleStatus {
    /** Built from the database, not yet sent anywhere. */
    RENDERED,
    /** The agent has staged it and `nginx -t` succeeded against it. */
    VALIDATED,
    /** Currently served by the instance. */
    ACTIVE,
    /** Was active, replaced by a newer bundle. Retained as a rollback target. */
    SUPERSEDED,
    /** Rejected by validation. Kept so the failure can be inspected. */
    FAILED
}
