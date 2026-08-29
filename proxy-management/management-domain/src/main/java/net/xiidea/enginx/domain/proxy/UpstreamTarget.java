package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One backend server behind a proxy site.
 *
 * <p>The host is deliberately <em>not</em> a URL. Accepting a free-form URL would let a user
 * smuggle a path, a query string, or directive-terminating characters into
 * {@code proxy_pass}. Scheme, host and port are captured separately and each is validated
 * on its own terms, so the renderer can only ever emit a well-formed upstream.
 */
public record UpstreamTarget(
        String scheme,
        String host,
        int port,
        int weight,
        int maxFails,
        int failTimeoutSeconds,
        boolean backup) {

    private static final Pattern HOSTNAME =
            Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))*$");
    private static final Pattern IPV4 =
            Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6_BRACKETED = Pattern.compile("^\\[[0-9a-f:]{2,45}]$");

    public UpstreamTarget {
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        scheme = scheme.trim().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ValidationException("upstreams.scheme", "Upstream scheme must be http or https");
        }

        if (host == null || host.isBlank()) {
            throw new ValidationException("upstreams.host", "Upstream host must not be blank");
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.length() > 253) {
            throw new ValidationException("upstreams.host", "Upstream host must not exceed 253 characters");
        }
        boolean valid = IPV4.matcher(host).matches()
                || IPV6_BRACKETED.matcher(host).matches()
                || isHostname(host);
        if (!valid) {
            throw new ValidationException("upstreams.host",
                    "'" + host + "' is not a valid hostname or IP address. Supply the host alone, without a scheme or path.");
        }

        if (port < 1 || port > 65535) {
            throw new ValidationException("upstreams.port", "Upstream port must be between 1 and 65535");
        }
        if (weight < 1 || weight > 100) {
            throw new ValidationException("upstreams.weight", "Upstream weight must be between 1 and 100");
        }
        if (maxFails < 0 || maxFails > 100) {
            throw new ValidationException("upstreams.maxFails", "maxFails must be between 0 and 100");
        }
        if (failTimeoutSeconds < 1 || failTimeoutSeconds > 3600) {
            throw new ValidationException("upstreams.failTimeoutSeconds", "failTimeout must be between 1 and 3600 seconds");
        }
    }

    /**
     * RFC 1123 section 2.1: the top-level label of a hostname must not be entirely numeric.
     * Without this rule a mistyped address such as {@code 999.1.1.1} would be accepted as a
     * hostname, and the mistake would only surface as a resolution failure at deployment time.
     */
    private static boolean isHostname(String host) {
        if (!HOSTNAME.matcher(host).matches()) {
            return false;
        }
        String topLevel = host.substring(host.lastIndexOf('.') + 1);
        return !topLevel.chars().allMatch(Character::isDigit);
    }

    public static UpstreamTarget of(String scheme, String host, int port) {
        return new UpstreamTarget(scheme, host, port, 1, 3, 10, false);
    }

    /** Rendered form for an {@code upstream} block entry, for example {@code 10.0.0.1:8080}. */
    public String authority() {
        return host + ":" + port;
    }
}
