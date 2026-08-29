package net.xiidea.enginx.domain.proxy;

/**
 * The operator's intent for a site. Only a user changes this; the lifecycle engine
 * never does. See architecture decision AD-6.
 */
public enum AdminState {
    ENABLED,
    DISABLED
}
