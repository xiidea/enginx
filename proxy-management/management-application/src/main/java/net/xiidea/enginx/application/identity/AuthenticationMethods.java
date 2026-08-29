package net.xiidea.enginx.application.identity;

/**
 * Which ways in this deployment offers.
 *
 * <p>Read by an unauthenticated endpoint so the console can render the right login screen. It
 * exposes only what a login page needs — never whether a particular account exists, and never
 * anything that would help someone choose a target.
 */
public interface AuthenticationMethods {

    boolean localEnabled();

    boolean oidcEnabled();

    /** The issuer a browser should be redirected to, or null when OIDC is off. */
    String oidcIssuer();

    /** The public client the console authenticates as, or null when OIDC is off. */
    String oidcClientId();
}
