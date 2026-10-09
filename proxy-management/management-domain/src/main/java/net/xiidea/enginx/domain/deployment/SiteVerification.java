package net.xiidea.enginx.domain.deployment;

/**
 * What the host reported when asked whether it serves a name.
 *
 * <p>{@code identified} is the signal. The probe asks for the path every rendered site answers with
 * its marker — {@code <site id> v<version> <fingerprint>} — and only the marker of the bundle just
 * deployed counts. Merely responding proves nothing on a host that already ran NGINX, where a
 * default page answers every name with 200; and the site's id alone proves nothing after an update,
 * where a reload NGINX accepted but did not apply leaves the previous config answering with the
 * same id.
 *
 * @param responded      whether anything answered on the port
 * @param statusCode     the status returned, or 0 when nothing responded
 * @param identified     whether the answer was exactly the expected marker
 * @param observedMarker what came back at the marker path, or null when it was not a marker
 * @param expectedMarker what the deployed bundle renders for this site
 * @param error          why no response arrived, or null
 */
public record SiteVerification(String serverName, boolean responded, int statusCode, boolean identified,
                               String observedMarker, String expectedMarker, String error) {

    /** Served by the configuration just deployed. */
    public boolean served() {
        return responded && identified;
    }

    /** This site's own server block answered, but from a different render of it. */
    public boolean olderConfigurationLive() {
        return responded && !identified && observedMarker != null && expectedMarker != null
                && siteIdOf(observedMarker).equals(siteIdOf(expectedMarker));
    }

    public String describe() {
        if (!responded) {
            return serverName + " → no response (" + (error == null ? "unknown" : error) + ")";
        }
        if (identified) {
            return serverName + " → served (" + revisionOf(expectedMarker) + ")";
        }
        if (olderConfigurationLive()) {
            return serverName + " → an older configuration is still live (" + revisionOf(observedMarker)
                    + ", expected " + revisionOf(expectedMarker) + "): the reload did not take effect";
        }
        return serverName + " → answered " + statusCode + ", but not by this site's configuration";
    }

    private static String siteIdOf(String marker) {
        int space = marker.indexOf(' ');
        return space < 0 ? marker : marker.substring(0, space);
    }

    /** {@code v12 3fa9c1d2…}: the part of a marker a person compares. */
    private static String revisionOf(String marker) {
        if (marker == null) {
            return "unknown";
        }
        int space = marker.indexOf(' ');
        return space < 0 ? marker : marker.substring(space + 1);
    }
}
