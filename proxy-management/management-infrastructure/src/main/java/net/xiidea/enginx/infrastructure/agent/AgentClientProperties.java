package net.xiidea.enginx.infrastructure.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param keyStore       PKCS#12 holding the management client certificate and key
 * @param trustStore     PKCS#12 holding the CA that signs agent certificates
 * @param verifyHostname whether to require the agent certificate's SAN to match the host it was
 *                       reached at. On by default; only a deployment with an unavoidable naming
 *                       mismatch should turn it off, and pinning still applies either way.
 */
@ConfigurationProperties(prefix = "enginx.agent")
public record AgentClientProperties(
        String keyStore,
        String keyStorePassword,
        String trustStore,
        String trustStorePassword,
        Duration connectTimeout,
        Duration requestTimeout,
        boolean verifyHostname) {

    public AgentClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
    }

    public boolean isConfigured() {
        return keyStore != null && !keyStore.isBlank() && trustStore != null && !trustStore.isBlank();
    }
}
