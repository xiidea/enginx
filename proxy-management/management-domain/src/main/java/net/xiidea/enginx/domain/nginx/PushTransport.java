package net.xiidea.enginx.domain.nginx;

/**
 * How the management server proves who it is to a host it dials.
 *
 * <p>Meaningful only for {@link ConnectivityMode#PUSH}. A pull host is never dialled.
 */
public enum PushTransport {

    /**
     * HTTPS with a client certificate, and the agent's certificate fingerprint pinned here. The
     * stronger of the two: each side proves its identity cryptographically.
     */
    MTLS,

    /**
     * HTTP or HTTPS with a pre-shared bearer token. For a host behind a proxy or tunnel that
     * terminates TLS and so cannot pass a client certificate through.
     */
    HTTP_TOKEN;

    public boolean isToken() {
        return this == HTTP_TOKEN;
    }
}
