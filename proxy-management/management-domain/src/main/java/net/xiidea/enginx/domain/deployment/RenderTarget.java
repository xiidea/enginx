package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.nginx.NginxInstance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the renderer needs to know about the host it renders for.
 *
 * <p>The configuration is otherwise host-independent. These two are not: NGINX grammar changed
 * under the platform's feet, and a host that already ran NGINX may already have a server that
 * answers unmatched names.
 *
 * @param nginxVersion         the version the host's agent last reported, or null before it has
 *                             reported one
 * @param defaultServerManaged whether the bundle carries the catch-all servers. False on a host
 *                             that keeps its own {@code default_server}, where two would fail
 *                             validation on every deployment
 */
public record RenderTarget(String nginxVersion, boolean defaultServerManaged) {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    /** A host nothing is known about yet: the most widely accepted grammar, and the catch-all. */
    public static final RenderTarget UNKNOWN = new RenderTarget(null, true);

    public static RenderTarget of(NginxInstance instance) {
        return new RenderTarget(instance.nginxVersion(), instance.defaultServerManaged());
    }

    /**
     * Whether the host understands the {@code http2 on;} directive, added in 1.25.1.
     *
     * <p>Before it, HTTP/2 is a {@code listen} parameter, and {@code http2 on;} fails validation —
     * which is every stock NGINX on current LTS distributions. The parameter form still works after
     * it, with a deprecation warning that does not fail {@code nginx -t}, so an unknown version gets
     * the form every version accepts.
     */
    public boolean supportsHttp2Directive() {
        if (nginxVersion == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(nginxVersion.trim());
        if (!matcher.find()) {
            return false;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        if (major != 1) {
            return major > 1;
        }
        return minor > 25 || (minor == 25 && patch >= 1);
    }
}
