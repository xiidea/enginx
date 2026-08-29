package net.xiidea.enginx.application.shared;

/**
 * Who is performing the current operation, and from where. Populated from the OIDC token
 * and the servlet request by the security module.
 */
public record Actor(String subject, String username, String ipAddress, String userAgent) {

    public static Actor system() {
        return new Actor("system", "system", null, null);
    }
}
