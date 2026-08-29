package net.xiidea.enginx.domain.nginx;

/**
 * How the platform and a host reach each other.
 *
 * <p>The choice is per host because it is a property of the network the host sits on, not of the
 * platform. An estate can hold both.
 */
public enum ConnectivityMode {

    /**
     * The management server dials the agent and pins its certificate fingerprint.
     *
     * <p>Requires inbound 8443 on the host, reachable from the management plane. Stronger
     * authentication of the host — a CA-signed certificate alone cannot impersonate it — at the
     * cost of needing a route to it.
     */
    PUSH,

    /**
     * The agent dials the management server and asks for work.
     *
     * <p>Needs no inbound connectivity at all, so a host behind NAT or in another network can be
     * managed. The host authenticates with a bearer token issued at registration, which is a
     * weaker proof of identity than a pinned certificate; issuing agent certificates is the
     * intended replacement.
     */
    PULL;

    public boolean isPush() {
        return this == PUSH;
    }

    public boolean isPull() {
        return this == PULL;
    }
}
