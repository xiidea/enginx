package net.xiidea.enginx.infrastructure.agent;

import com.sun.net.httpserver.HttpServer;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.nginx.ConnectivityMode;
import net.xiidea.enginx.domain.nginx.InstanceStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.PushTransport;
import net.xiidea.enginx.infrastructure.crypto.CryptoProperties;
import net.xiidea.enginx.infrastructure.crypto.EnvelopeEncryptionService;
import net.xiidea.enginx.infrastructure.crypto.EnvironmentKekProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token transport against a real socket: what goes on the wire is the part worth checking.
 */
class HttpNginxAgentClientTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer agent;
    private EnvelopeEncryptionService encryption;
    private HttpNginxAgentClient client;

    @BeforeEach
    void start() throws IOException {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/agent/v1/status", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"agentVersion":"1.0.0","nginxVersion":"1.27.5","nginxRunning":true,
                     "activeBundleId":"b1","configTestOk":true,"certificates":[]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        agent.start();

        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        encryption = new EnvelopeEncryptionService(new EnvironmentKekProvider(
                new CryptoProperties("k1", Map.of("k1", Base64.getEncoder().encodeToString(key)))));

        AgentClientProperties properties = new AgentClientProperties(null, null, null, null, null, null);
        AgentTokenSecrets tokens = new AgentTokenSecrets(null, encryption);
        client = new HttpNginxAgentClient(properties, new MtlsAgentConnection(properties),
                new TokenAgentConnection(properties, tokens));
    }

    @AfterEach
    void stop() {
        agent.stop(0);
    }

    private String agentUrl() {
        return "http://127.0.0.1:" + agent.getAddress().getPort();
    }

    @Test
    void aTokenHostIsCalledWithItsTokenAsABearerCredential() {
        NginxInstance instance = NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                agentUrl(), PushTransport.HTTP_TOKEN, null, encryption.encrypt(TOKEN), "prod", NOW);

        AgentStatus status = client.status(instance);

        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(status.activeBundleId()).isEqualTo("b1");
    }

    @Test
    void aRotatedTokenIsOpenedAfreshRatherThanServedFromCache() {
        NginxInstance instance = NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                agentUrl(), PushTransport.HTTP_TOKEN, null, encryption.encrypt(TOKEN), "prod", NOW);
        client.status(instance);

        String rotated = "fedcba9876543210fedcba9876543210";
        instance.agentTokenRotated(encryption.encrypt(rotated), NOW);
        client.status(instance);

        assertThat(authorization.get()).isEqualTo("Bearer " + rotated);
    }

    @Test
    void anUnopenableTokenIsAPermanentFailureNotARetry() {
        EncryptedSecret foreign = new EncryptedSecret(new byte[]{1, 2, 3}, new byte[]{4}, "gone", "AES-256-GCM",
                new byte[]{5}, null);
        NginxInstance instance = NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                agentUrl(), PushTransport.HTTP_TOKEN, null, foreign, "prod", NOW);

        assertThatThrownBy(() -> client.status(instance))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).isRetryable()).isFalse());
        assertThat(authorization.get()).isNull();
    }

    @Test
    void anUndiallableUrlIsAPermanentFailureNotACrash() {
        // Registration refuses this scheme; a row written before it did must still fail cleanly.
        NginxInstance instance = NginxInstance.rehydrate(UUID.randomUUID(), "edge", "edge.internal",
                URI.create("grpc://127.0.0.1:1"), null, ConnectivityMode.PUSH, PushTransport.HTTP_TOKEN,
                encryption.encrypt(TOKEN), "prod", InstanceStatus.UNKNOWN, null, null, null, NOW, NOW, 0L);

        assertThatThrownBy(() -> client.status(instance))
                .isInstanceOf(AgentException.class)
                .satisfies(e -> assertThat(((AgentException) e).isRetryable()).isFalse());
    }
}
