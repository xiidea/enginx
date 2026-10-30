package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How one location proxies, where it differs from its site. Every field is optional, and an unset
 * one is the site's: a location with none of them proxies exactly as the site does.
 *
 * @param requestHeaders added to the site's request headers; one with the same name replaces the
 *                       site's for this location. Response headers stay site-wide.
 */
public record LocationProxySettings(Integer connectSeconds, Integer readSeconds, Integer sendSeconds,
                                    Long maxBodySizeBytes, Boolean websocketEnabled,
                                    List<ProxySiteHeader> requestHeaders) {

    private static final int MAX_HEADERS = 32;
    private static final LocationProxySettings NONE = new LocationProxySettings(null, null, null, null, null, List.of());

    public LocationProxySettings {
        requestHeaders = requestHeaders == null ? List.of() : List.copyOf(requestHeaders);
        if (requestHeaders.size() > MAX_HEADERS) {
            throw new ValidationException("locations.requestHeaders",
                    "A location may not have more than " + MAX_HEADERS + " request headers");
        }
        Set<String> seen = new HashSet<>();
        for (ProxySiteHeader header : requestHeaders) {
            if (header.direction() != HeaderDirection.REQUEST) {
                throw new ValidationException("locations.requestHeaders",
                        "A location sets request headers only; response headers are the site's");
            }
            if (!seen.add(header.name().toLowerCase(Locale.ROOT))) {
                throw new ValidationException("locations.requestHeaders", "Duplicate request header " + header.name());
            }
        }
        // Checked as the site's are, by building them over the site's defaults: a value out of
        // range fails here with the same message rather than at render time.
        ProxyTimeouts defaults = ProxyTimeouts.defaults();
        new ProxyTimeouts(connectSeconds == null ? defaults.connectSeconds() : connectSeconds,
                readSeconds == null ? defaults.readSeconds() : readSeconds,
                sendSeconds == null ? defaults.sendSeconds() : sendSeconds,
                maxBodySizeBytes == null ? defaults.maxBodySizeBytes() : maxBodySizeBytes);
    }

    public static LocationProxySettings none() {
        return NONE;
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    /** The site's timeouts and body size, with this location's in place of any it sets. */
    public ProxyTimeouts timeoutsOver(ProxyTimeouts site) {
        return new ProxyTimeouts(connectSeconds == null ? site.connectSeconds() : connectSeconds,
                readSeconds == null ? site.readSeconds() : readSeconds,
                sendSeconds == null ? site.sendSeconds() : sendSeconds,
                maxBodySizeBytes == null ? site.maxBodySizeBytes() : maxBodySizeBytes);
    }

    public boolean websocketOver(boolean site) {
        return websocketEnabled == null ? site : websocketEnabled;
    }

    /** The site's request headers, with this location's added, replacing any of the same name. */
    public List<ProxySiteHeader> requestHeadersOver(List<ProxySiteHeader> siteHeaders) {
        Set<String> replaced = new HashSet<>();
        requestHeaders.forEach(header -> replaced.add(header.name().toLowerCase(Locale.ROOT)));
        List<ProxySiteHeader> merged = new java.util.ArrayList<>(siteHeaders.stream()
                .filter(header -> header.direction() == HeaderDirection.REQUEST)
                .filter(header -> !replaced.contains(header.name().toLowerCase(Locale.ROOT)))
                .toList());
        merged.addAll(requestHeaders);
        return merged;
    }
}
