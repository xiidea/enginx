package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;

/**
 * A pre-shared bearer token, for a host behind a proxy that cannot pass a client certificate.
 *
 * <p>One client for every such host: nothing about the connection is specific to one of them.
 * Over HTTPS the agent's certificate is checked against the JVM trust store, since a proxy in
 * front of it usually presents a publicly trusted one.
 */
@Component
class TokenAgentConnection implements AgentConnection {

    private final HttpClient client;
    private final AgentTokenSecrets tokens;

    TokenAgentConnection(AgentClientProperties properties, AgentTokenSecrets tokens) {
        this.tokens = tokens;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public HttpClient clientFor(NginxInstance instance) {
        return client;
    }

    @Override
    public void authenticate(HttpRequest.Builder request, NginxInstance instance) {
        request.header("Authorization", "Bearer " + tokens.reveal(instance));
    }
}
