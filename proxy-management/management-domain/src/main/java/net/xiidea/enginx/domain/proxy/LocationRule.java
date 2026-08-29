package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.regex.Pattern;

/**
 * A path prefix routed by a site. Regex locations are intentionally not offered to normal
 * users: they are the easiest way to write an NGINX configuration that is valid but wrong.
 */
public record LocationRule(String pathPattern, LocationMatchType matchType, int sortOrder) {

    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._~/*-]{0,255}$");

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
    }

    public static LocationRule root() {
        return new LocationRule("/", LocationMatchType.PREFIX, 0);
    }
}
