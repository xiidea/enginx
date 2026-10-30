package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A path a site routes, and how. Regex locations are intentionally not offered to normal users:
 * they are the easiest way to write an NGINX configuration that is valid but wrong.
 *
 * <p>Everything past the path is optional and inherited from the site when unset, so a location
 * with nothing else proxies exactly as its site does, which is what every location did before.
 *
 * @param publicAccess        reached without signing in on a protected site, for a webhook or a
 *                            health endpoint a third party calls. Ignored on an unprotected site.
 * @param upstreams           a pool of its own; empty for the site's
 * @param loadBalancingMethod how its own pool is balanced; null for round robin, and only with a
 *                            pool of its own
 */
public record LocationRule(String pathPattern, LocationMatchType matchType, int sortOrder,
                           boolean publicAccess, List<UpstreamTarget> upstreams,
                           LoadBalancingMethod loadBalancingMethod, LocationProxySettings settings) {

    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._~/*-]{0,255}$");
    private static final int MAX_UPSTREAMS = 32;

    /**
     * The path VERIFY's protection probe asks for besides {@code /}, followed by random hex. A
     * public location that could match it would make the probe find a protected site open.
     */
    public static final String PROTECTION_PROBE_PREFIX = "/.enginx-verify-";

    public LocationRule {
        if (pathPattern == null || pathPattern.isBlank()) {
            throw new ValidationException("locations.pathPattern", "Location path must not be blank");
        }
        pathPattern = pathPattern.trim();
        if (!PATH.matcher(pathPattern).matches()) {
            throw new ValidationException("locations.pathPattern",
                    "Location path must start with '/' and contain only unreserved URL characters");
        }
        if (matchType == null) {
            matchType = LocationMatchType.PREFIX;
        }

        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        if (upstreams.size() > MAX_UPSTREAMS) {
            throw new ValidationException("locations.upstreams",
                    "A location may not have more than " + MAX_UPSTREAMS + " upstreams");
        }
        Set<String> seen = new HashSet<>();
        for (UpstreamTarget upstream : upstreams) {
            if (!seen.add(upstream.scheme() + "://" + upstream.authority())) {
                throw new ValidationException("locations.upstreams", "Duplicate upstream " + upstream.authority()
                        + " for " + pathPattern);
            }
        }
        if (!upstreams.isEmpty() && upstreams.stream().allMatch(UpstreamTarget::backup)) {
            throw new ValidationException("locations.upstreams",
                    "At least one of " + pathPattern + "'s upstreams must be a primary, not a backup");
        }
        if (upstreams.isEmpty() && loadBalancingMethod != null) {
            throw new ValidationException("locations.loadBalancingMethod",
                    pathPattern + " uses the site's upstreams, and with them the site's load balancing");
        }
        settings = settings == null ? LocationProxySettings.none() : settings;

        if (publicAccess) {
            requirePublicable(pathPattern, matchType);
        }
    }

    /** A location as every location was before they could differ from their site. */
    public LocationRule(String pathPattern, LocationMatchType matchType, int sortOrder) {
        this(pathPattern, matchType, sortOrder, false, List.of(), null, LocationProxySettings.none());
    }

    public static LocationRule root() {
        return new LocationRule("/", LocationMatchType.PREFIX, 0);
    }

    public boolean isRoot() {
        return pathPattern.equals("/");
    }

    public boolean hasOwnUpstreams() {
        return !upstreams.isEmpty();
    }

    /** The site's pool, or this location's own. */
    public List<UpstreamTarget> upstreamsOver(List<UpstreamTarget> site) {
        return upstreams.isEmpty() ? site : upstreams;
    }

    /**
     * Where a public location may not be. The root is the whole site: making it public is turning
     * protection off, which is the protection setting's job. The platform's own paths answer
     * before any location, and VERIFY's probe must always meet the site's protection.
     */
    private static void requirePublicable(String path, LocationMatchType matchType) {
        if (path.equals("/")) {
            throw new ValidationException("locations.publicAccess",
                    "The root location cannot be public. To let everyone in, turn the site's protection off");
        }
        if (path.startsWith("/.enginx") || path.startsWith("/.well-known/enginx")
                || (matchType == LocationMatchType.PREFIX && PROTECTION_PROBE_PREFIX.startsWith(path))) {
            throw new ValidationException("locations.publicAccess",
                    path + " is the platform's own, and cannot be made public");
        }
    }
}
