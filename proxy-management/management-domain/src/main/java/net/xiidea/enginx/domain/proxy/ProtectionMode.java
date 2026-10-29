package net.xiidea.enginx.domain.proxy;

/** How a site gates who may reach it. */
public enum ProtectionMode {
    /** Public: anyone who can reach the host can reach the site. */
    NONE,
    /** HTTP basic authentication against the site's own user list. */
    BASIC,
    /**
     * A one-time code sent to an allowed email address, checked by the host's gate, which NGINX
     * consults on every request.
     */
    EMAIL_OTP,
    /**
     * Sign-in at the platform's identity provider, checked by the host's gate; who is let in is
     * the site's {@link OidcAccessPolicy}.
     */
    OIDC;

    /** Whether the host's gate, rather than NGINX alone, decides who gets in. */
    public boolean usesGate() {
        return this == EMAIL_OTP || this == OIDC;
    }
}
