package net.xiidea.enginx.domain.proxy;

/**
 * How a site gates who may reach it.
 *
 * <p>Email one-time codes and OIDC sign-in are planned as further modes, served by a host-local
 * gate; they are added here when something can actually enforce them.
 */
public enum ProtectionMode {
    /** Public: anyone who can reach the host can reach the site. */
    NONE,
    /** HTTP basic authentication against the site's own user list. */
    BASIC
}
