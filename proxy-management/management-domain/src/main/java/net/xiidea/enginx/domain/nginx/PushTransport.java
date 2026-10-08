package net.xiidea.enginx.domain.nginx;

/**
 * The protocol and authentication mode used when the management server dials a PUSH agent.
 */
public enum PushTransport {

    /**
     * HTTPS REST dial with mutual TLS (mTLS) and SHA-256 certificate fingerprint verification.
     */
    MTLS,

    /**
     * HTTP/HTTPS REST dial using a pre-shared Bearer token (`Authorization: Bearer <token>`).
     */
    HTTP_TOKEN,

    /**
     * gRPC over HTTP/2 dial using binary protobuf RPCs and Bearer token metadata.
     */
    GRPC_TOKEN;

    public boolean isMtls() {
        return this == MTLS;
    }

    public boolean isToken() {
        return this == HTTP_TOKEN || this == GRPC_TOKEN;
    }

    public boolean isGrpc() {
        return this == GRPC_TOKEN;
    }
}
