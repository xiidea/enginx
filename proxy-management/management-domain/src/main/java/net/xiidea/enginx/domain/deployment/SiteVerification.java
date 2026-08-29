package net.xiidea.enginx.domain.deployment;

/**
 * What the host reported when asked whether it serves a name.
 *
 * <p>{@code responded} is the signal, not {@code statusCode}. A site with forced HTTPS answers
 * port 80 with a 301, and a backend may legitimately return 404 or 502 for the site root — none
 * of those mean the configuration failed to take effect. What this detects is the case a
 * successful reload cannot rule out: NGINX loaded the configuration, and the name is nonetheless
 * served by nothing.
 *
 * @param statusCode the status returned, or 0 when nothing responded
 * @param error      why no response arrived, or null
 */
public record SiteVerification(String serverName, boolean responded, int statusCode, String error) {

    public String describe() {
        return responded
                ? serverName + " → " + statusCode
                : serverName + " → no response (" + (error == null ? "unknown" : error) + ")";
    }
}
