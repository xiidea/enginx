package net.xiidea.enginx.domain.deployment;

/**
 * What the host reported when asked whether it serves a name.
 *
 * <p>{@code identified} is the signal. The probe asks for the path every rendered site answers with
 * its own id, so a matching answer proves the platform's configuration for that name is the one
 * serving it. Merely responding proves nothing on a host that already ran NGINX: a distribution's
 * default page answers every name with 200, which once made a site that was not served at all
 * verify as healthy.
 *
 * @param responded  whether anything answered on the port
 * @param statusCode the status returned, or 0 when nothing responded
 * @param identified whether the answer carried this site's marker
 * @param error      why no response arrived, or null
 */
public record SiteVerification(String serverName, boolean responded, int statusCode, boolean identified,
                               String error) {

    /** Served by the configuration just deployed. */
    public boolean served() {
        return responded && identified;
    }

    public String describe() {
        if (!responded) {
            return serverName + " → no response (" + (error == null ? "unknown" : error) + ")";
        }
        return identified
                ? serverName + " → served"
                : serverName + " → answered " + statusCode + ", but not by this site's configuration";
    }
}
