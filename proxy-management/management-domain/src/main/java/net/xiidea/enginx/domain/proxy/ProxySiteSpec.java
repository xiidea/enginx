package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The complete, validated description of what a proxy site should be.
 *
 * <p>This is the single input the configuration renderer will accept in Phase 4. Because every
 * component is itself a validated value object and this record enforces the cross-field rules,
 * there is exactly one place where malformed data could enter a generated NGINX file, and it
 * is this constructor.
 */
public record ProxySiteSpec(
        String name,
        DomainName domain,
        UUID nginxInstanceId,
        TimeWindow window,
        boolean sslEnabled,
        boolean forceHttps,
        boolean hstsEnabled,
        boolean websocketEnabled,
        UUID sslCertificateId,
        LoadBalancingMethod loadBalancingMethod,
        ProxyTimeouts timeouts,
        List<UpstreamTarget> upstreams,
        List<ProxySiteHeader> headers,
        List<LocationRule> locations,
        SiteProtection protection) {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_UPSTREAMS = 32;
    private static final int MAX_HEADERS = 32;
    private static final int MAX_LOCATIONS = 32;

    /** The header a gated site passes upstream with the signed-in visitor's address. */
    public static final String GATE_EMAIL_HEADER = "X-Enginx-Email";

    /** The header a gated site passes upstream with the signed-in visitor's username. */
    public static final String GATE_USER_HEADER = "X-Enginx-User";

    public ProxySiteSpec {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name", "Name must not be blank");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("name", "Name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        if (domain == null) {
            throw new ValidationException("domain", "Domain is required");
        }
        if (nginxInstanceId == null) {
            throw new ValidationException("nginxInstanceId", "An NGINX instance must be selected");
        }

        window = window == null ? TimeWindow.unbounded() : window;
        loadBalancingMethod = loadBalancingMethod == null ? LoadBalancingMethod.ROUND_ROBIN : loadBalancingMethod;
        timeouts = timeouts == null ? ProxyTimeouts.defaults() : timeouts;

        upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
        if (upstreams.isEmpty()) {
            throw new ValidationException("upstreams", "At least one upstream is required");
        }
        if (upstreams.size() > MAX_UPSTREAMS) {
            throw new ValidationException("upstreams", "A site may not have more than " + MAX_UPSTREAMS + " upstreams");
        }
        Set<String> seenUpstreams = new HashSet<>();
        for (UpstreamTarget upstream : upstreams) {
            if (!seenUpstreams.add(upstream.scheme() + "://" + upstream.authority())) {
                throw new ValidationException("upstreams", "Duplicate upstream " + upstream.authority());
            }
        }
        if (upstreams.stream().allMatch(UpstreamTarget::backup)) {
            throw new ValidationException("upstreams", "At least one upstream must be a primary, not a backup");
        }

        headers = headers == null ? List.of() : List.copyOf(headers);
        if (headers.size() > MAX_HEADERS) {
            throw new ValidationException("headers", "A site may not have more than " + MAX_HEADERS + " headers");
        }
        Set<String> seenHeaders = new HashSet<>();
        for (ProxySiteHeader header : headers) {
            if (!seenHeaders.add(header.direction() + " " + header.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new ValidationException("headers", "Duplicate " + header.direction() + " header " + header.name());
            }
        }

        locations = locations == null || locations.isEmpty() ? List.of(LocationRule.root()) : List.copyOf(locations);
        if (locations.size() > MAX_LOCATIONS) {
            throw new ValidationException("locations", "A site may not have more than " + MAX_LOCATIONS + " locations");
        }
        Set<String> seenPaths = new HashSet<>();
        for (LocationRule location : locations) {
            if (!seenPaths.add(location.matchType() + " " + location.pathPattern())) {
                throw new ValidationException("locations", "Duplicate location " + location.pathPattern());
            }
        }

        if (sslEnabled && sslCertificateId == null) {
            throw new ValidationException("sslCertificateId", "A certificate must be selected when SSL is enabled");
        }
        if (!sslEnabled && forceHttps) {
            throw new ValidationException("forceHttps", "HTTP to HTTPS redirect requires SSL to be enabled");
        }
        if (!sslEnabled && hstsEnabled) {
            throw new ValidationException("hstsEnabled", "HSTS requires SSL to be enabled");
        }

        protection = protection == null ? SiteProtection.none() : protection;
        // Credentials must never cross plain HTTP. With forceHttps the port-80 server only
        // redirects, so the only server that ever asks for them is the TLS one.
        if (protection.isProtected() && !(sslEnabled && forceHttps)) {
            throw new ValidationException("protection.mode",
                    "Protecting a site requires SSL with the HTTP to HTTPS redirect enabled");
        }
        if (protection.mode().usesGate()) {
            for (ProxySiteHeader header : headers) {
                if (header.direction() == HeaderDirection.REQUEST
                        && (header.name().equalsIgnoreCase(GATE_EMAIL_HEADER)
                            || header.name().equalsIgnoreCase(GATE_USER_HEADER))) {
                    throw new ValidationException("headers",
                            header.name() + " is set by the gate on a protected site and cannot be overridden");
                }
            }
        }
    }

    /** An unprotected site: what every site was before protection existed. */
    public ProxySiteSpec(String name, DomainName domain, UUID nginxInstanceId, TimeWindow window,
                         boolean sslEnabled, boolean forceHttps, boolean hstsEnabled, boolean websocketEnabled,
                         UUID sslCertificateId, LoadBalancingMethod loadBalancingMethod, ProxyTimeouts timeouts,
                         List<UpstreamTarget> upstreams, List<ProxySiteHeader> headers, List<LocationRule> locations) {
        this(name, domain, nginxInstanceId, window, sslEnabled, forceHttps, hstsEnabled, websocketEnabled,
                sslCertificateId, loadBalancingMethod, timeouts, upstreams, headers, locations, null);
    }

    public ProxySiteSpec withIdentity(String newName, DomainName newDomain) {
        return new ProxySiteSpec(newName, newDomain, nginxInstanceId, window, sslEnabled, forceHttps,
                hstsEnabled, websocketEnabled, sslCertificateId, loadBalancingMethod, timeouts,
                upstreams, headers, locations, protection);
    }

    public ProxySiteSpec withWindow(TimeWindow newWindow) {
        return new ProxySiteSpec(name, domain, nginxInstanceId, newWindow, sslEnabled, forceHttps,
                hstsEnabled, websocketEnabled, sslCertificateId, loadBalancingMethod, timeouts,
                upstreams, headers, locations, protection);
    }

    public ProxySiteSpec withProtection(SiteProtection newProtection) {
        return new ProxySiteSpec(name, domain, nginxInstanceId, window, sslEnabled, forceHttps,
                hstsEnabled, websocketEnabled, sslCertificateId, loadBalancingMethod, timeouts,
                upstreams, headers, locations, newProtection);
    }
}
