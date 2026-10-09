package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutual TLS, with the agent's certificate pinned.
 *
 * <p>One {@link HttpClient} is cached per agent certificate fingerprint, because the pin lives in
 * the TLS context. That also gives each instance its own connection pool, so a slow or wedged
 * host cannot starve deployments to the others.
 *
 * <p>Hostname verification is always on: the JDK client checks the certificate's SAN against the
 * host in agent_base_url on every connection, and the pin is checked in addition. There is
 * deliberately no switch to turn it off — a mismatch is fixed by issuing the certificate with the
 * right name, which the PKI script does.
 */
@Component
class MtlsAgentConnection implements AgentConnection {

    private final AgentClientProperties properties;
    private final Map<String, HttpClient> clientsByFingerprint = new ConcurrentHashMap<>();

    MtlsAgentConnection(AgentClientProperties properties) {
        this.properties = properties;
    }

    @Override
    public HttpClient clientFor(NginxInstance instance) {
        return clientsByFingerprint.computeIfAbsent(instance.agentCertFingerprint(), fingerprint -> {
            if (!properties.isConfigured()) {
                throw new AgentException(
                        "Agent mTLS is not configured: set enginx.agent.key-store and trust-store", false);
            }
            try {
                SSLContext sslContext = AgentTlsFactory.create(properties, fingerprint);
                return HttpClient.newBuilder()
                        .sslContext(sslContext)
                        .connectTimeout(properties.connectTimeout())
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
            } catch (Exception e) {
                throw new AgentException("Could not build the agent TLS context: " + e.getMessage(), e, false);
            }
        });
    }

    @Override
    public void authenticate(HttpRequest.Builder request, NginxInstance instance) {
        // The client certificate in the handshake is the authentication.
    }
}
