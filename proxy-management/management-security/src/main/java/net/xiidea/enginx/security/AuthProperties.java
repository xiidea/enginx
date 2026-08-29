package net.xiidea.enginx.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Which authentication methods this deployment offers.
 *
 * <p>Both can be on at once. A migration wants exactly that: local accounts keep working while
 * people move to the identity provider, and the permission model does not distinguish them.
 *
 * @param oidcEnabled          accept tokens from the configured OIDC issuer
 * @param localEnabled         accept a username and password held by the platform
 * @param jwtSecret            signs locally issued tokens. Required when local is enabled: there
 *                             is no safe default, and a generated one would differ per replica
 *                             so a token minted by one would be rejected by the next
 * @param tokenTtl             how long a locally issued token lasts
 * @param bootstrapUsername    account created on first start when no local account exists
 * @param bootstrapPassword    its initial password. The account is flagged to change it, because
 *                             a password that came from configuration has been readable by
 *                             anything that can read configuration
 */
@ConfigurationProperties(prefix = "enginx.auth")
public record AuthProperties(
        boolean oidcEnabled,
        boolean localEnabled,
        String jwtSecret,
        Duration tokenTtl,
        String bootstrapUsername,
        String bootstrapPassword) {

    /** The issuer stamped into locally minted tokens, and the one they are validated against. */
    public static final String LOCAL_ISSUER = "enginx-local";

    public AuthProperties {
        tokenTtl = tokenTtl == null ? Duration.ofHours(8) : tokenTtl;
        jwtSecret = jwtSecret == null || jwtSecret.isBlank() ? null : jwtSecret.trim();
        bootstrapUsername = bootstrapUsername == null || bootstrapUsername.isBlank()
                ? null : bootstrapUsername.trim();
        bootstrapPassword = bootstrapPassword == null || bootstrapPassword.isBlank()
                ? null : bootstrapPassword;
    }

    /**
     * Fails fast rather than at the first login.
     *
     * <p>An application with no way to authenticate anybody starts, reports healthy, and serves
     * 401 to everything — which looks like a broken token or a misconfigured proxy, and is the
     * kind of thing people debug for an hour before checking whether login was switched off.
     */
    public void validate() {
        if (!oidcEnabled && !localEnabled) {
            throw new IllegalStateException(
                    "Both enginx.auth.oidc-enabled and enginx.auth.local-enabled are false. "
                            + "Nobody could authenticate, so the platform will not start.");
        }
        if (localEnabled && jwtSecret == null) {
            throw new IllegalStateException(
                    "enginx.auth.local-enabled is true but enginx.auth.jwt-secret is not set. "
                            + "Generate one with: openssl rand -base64 48");
        }
        if (localEnabled && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            // HS256 keys shorter than the digest add no security and Nimbus refuses them anyway;
            // saying so here beats an obscure library error on the first login attempt.
            throw new IllegalStateException(
                    "enginx.auth.jwt-secret must be at least 32 bytes. "
                            + "Generate one with: openssl rand -base64 48");
        }
    }
}
