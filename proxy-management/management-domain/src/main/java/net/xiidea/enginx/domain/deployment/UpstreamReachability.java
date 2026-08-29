package net.xiidea.enginx.domain.deployment;

/**
 * Whether an upstream accepted a TCP connection from the host that will proxy to it.
 *
 * <p>Advisory, and honestly so. NGINX Open Source resolves upstream names once when the
 * configuration loads, so a name that answers now may still fail later, and one that refuses now
 * may be a service that has simply not started yet. This narrows the common case — a typo, or a
 * port nothing is listening on — and promises nothing beyond it.
 */
public record UpstreamReachability(String host, int port, boolean reachable, String error) {

    public String target() {
        return host + ":" + port;
    }
}
