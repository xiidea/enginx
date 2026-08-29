package net.xiidea.enginx.security.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.List;

/**
 * Which requests count as sensitive.
 *
 * <p>Kept as an explicit list rather than an annotation on each controller method. A limit that
 * has to be remembered at every new endpoint is a limit that will be missing from one of them, and
 * the missing one is discovered by whoever finds it first.
 *
 * <p>Read as: sensitive unless proven otherwise for these prefixes. A new sub-resource under
 * {@code /certificates} is therefore limited from the day it is written, not from the day someone
 * notices it was not.
 */
final class SensitivePaths {

    private static final PathMatcher MATCHER = new AntPathMatcher();

    private static final List<String> PATTERNS = List.of(
            // Issuance and renewal are counted by the authority, not by us. Exceeding their limit
            // is not a slowdown; it is a lockout measured in days.
            "/api/v1/certificates/**",
            // Each one renders a bundle, ships it to a host and reloads NGINX there.
            "/api/v1/deployments/**",
            "/api/v1/proxy-sites/*/deploy",
            "/api/v1/nginx-instances/*/deploy",
            // Login. Unauthenticated, so the limiter keys it by address — which is exactly the
            // right key for credential stuffing, and the only defence a public endpoint has.
            "/api/v1/auth/login",
            // Creating an account, changing a password, granting a role.
            "/api/v1/local-users/**",
            // Authorization changes. Brute-forcing these is how a foothold becomes an estate.
            "/api/v1/permissions/**",
            // Bulk removal from the shared identity directory. Not destructive to access, but
            // repeated at speed it would empty the list every grant author picks names from.
            "/api/v1/users/cleanup",
            // Registering an instance introduces a new host, and its trusted agent fingerprint.
            "/api/v1/nginx-instances/**");

    private SensitivePaths() {
    }

    static RateLimitTier tierFor(String path, String method) {
        boolean safe = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method);

        // A GET under a sensitive prefix is still just a read: listing certificates neither issues
        // one nor touches a host. Charging it to the strict bucket would exhaust an operator's
        // allowance by opening the certificates page.
        if (safe) {
            return RateLimitTier.READ;
        }
        for (String pattern : PATTERNS) {
            if (MATCHER.match(pattern, path)) {
                return RateLimitTier.SENSITIVE;
            }
        }
        return RateLimitTier.WRITE;
    }
}
