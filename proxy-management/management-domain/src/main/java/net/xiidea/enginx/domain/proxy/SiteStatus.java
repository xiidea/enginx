package net.xiidea.enginx.domain.proxy;

/**
 * The observed lifecycle state of a site. Derived from admin state, the activation
 * window and the last deployment result; never set directly by a user (AD-6).
 */
public enum SiteStatus {
    PENDING,
    ACTIVE,
    DISABLED,
    EXPIRED,
    ERROR;

    /** Sites in these states are omitted from a rendered configuration bundle. */
    public boolean isServable() {
        return this != DISABLED && this != EXPIRED;
    }
}
