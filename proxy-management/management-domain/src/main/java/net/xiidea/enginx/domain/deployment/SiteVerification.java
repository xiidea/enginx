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
 * @param expectedProtection {@code BASIC} or {@code GATE} when the site must refuse a visitor who
 *                       has not signed in, or null for a public site
 * @param protectionEnforced whether an unauthenticated request was refused as expected; null when
 *                       not checked
 * @param protectionDetail what the unauthenticated request got, when it was not refused
 */
public record SiteVerification(String serverName, boolean responded, int statusCode, boolean identified,
                               String observedMarker, String expectedMarker, String error,
                               String expectedProtection, Boolean protectionEnforced, String protectionDetail) {

    public SiteVerification(String serverName, boolean responded, int statusCode, boolean identified,
                            String observedMarker, String expectedMarker, String error) {
        this(serverName, responded, statusCode, identified, observedMarker, expectedMarker, error, null, null, null);
    }

    /**
     * Served by the configuration just deployed, and, for a protected site, refusing a visitor who
     * has not signed in. A protected site that lets anyone through is not served as configured: a
     * directive in a host's custom file, another server claiming the name, or files the workers
     * cannot read all produce exactly that, and none of them fails {@code nginx -t}.
     */
    public boolean served() {
        return responded && identified && protectionHolds();
    }

    private boolean protectionHolds() {
        return expectedProtection == null || Boolean.TRUE.equals(protectionEnforced);
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
        if (identified && !protectionHolds()) {
            return serverName + " → served (" + revisionOf(expectedMarker) + "), but its protection is not enforced: "
                    + (protectionDetail == null ? "it was not checked" : protectionDetail);
        }
        if (identified) {
            return serverName + " → served (" + revisionOf(expectedMarker) + ")"
                    + (expectedProtection == null ? "" : ", and refuses visitors who have not signed in");
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
