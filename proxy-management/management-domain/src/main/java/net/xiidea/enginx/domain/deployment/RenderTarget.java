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
 * @param agentVersion         the version of the agent on the host, or null before it has reported
 *                             one. Decides which file modes the bundle may rely on
 */
public record RenderTarget(String nginxVersion, boolean defaultServerManaged, String agentVersion) {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    /** The first agent release that writes {@code 0640} files group-readable by the NGINX workers. */
    static final int[] WORKER_READABLE_AGENT = {0, 2, 0};

    /** A host nothing is known about yet: the most widely accepted grammar, and the catch-all. */
    public static final RenderTarget UNKNOWN = new RenderTarget(null, true, null);

    public RenderTarget(String nginxVersion, boolean defaultServerManaged) {
        this(nginxVersion, defaultServerManaged, null);
    }

    public static RenderTarget of(NginxInstance instance) {
        return new RenderTarget(instance.nginxVersion(), instance.defaultServerManaged(), instance.agentVersion());
    }

    /**
     * Whether the host's agent writes {@link BundleFile.Access#WORKER_READABLE} files as {@code 0640}
     * owned by the NGINX worker group.
     *
     * <p>An older agent writes any sensitive file {@code 0600} root-owned. That still passes
     * {@code nginx -t} — the master runs as root — and then fails every request, because the
     * workers that read an htpasswd file cannot open it. Refusing to render is the only way to
     * keep that out of a deployment that would otherwise report success. A local {@code dev}
     * build is assumed current.
     */
    public boolean supportsWorkerReadableFiles() {
        if (agentVersion == null) {
            return false;
        }
        String version = agentVersion.trim();
        if (version.equals("dev")) {
            return true;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        for (int i = 0; i < WORKER_READABLE_AGENT.length; i++) {
            int part = Integer.parseInt(matcher.group(i + 1));
            if (part != WORKER_READABLE_AGENT[i]) {
                return part > WORKER_READABLE_AGENT[i];
            }
        }
        return true;
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
