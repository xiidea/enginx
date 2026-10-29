package net.xiidea.enginx.domain.proxy;

/**
 * How a site gates who may reach it.
 *
 * <p>OIDC sign-in is planned as a further mode, served by the same host-local gate as
 * {@link #EMAIL_OTP}; it is added here when something can actually enforce it.
 */
public enum ProtectionMode {
    /** Public: anyone who can reach the host can reach the site. */
    NONE,
    /** HTTP basic authentication against the site's own user list. */
    BASIC,
    /**
     * A one-time code sent to an allowed email address, checked by the host's gate, which NGINX
     * consults on every request.
     */
    EMAIL_OTP;

    /** Whether the host's gate, rather than NGINX alone, decides who gets in. */
    public boolean usesGate() {
        return this == EMAIL_OTP;
    }
}
