package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.PushTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingNginxAgentClientTest {

    @Mock
    private HttpNginxAgentClient mtlsClient;

    @Mock
    private TokenHttpNginxAgentClient tokenHttpClient;

    private RoutingNginxAgentClient routingClient;

    @BeforeEach
    void setUp() {
        routingClient = new RoutingNginxAgentClient(mtlsClient, tokenHttpClient);
    }

    @Test
    @DisplayName("Routes calls to mtlsClient when PushTransport is MTLS")
    void routesToMtlsClient() {
        NginxInstance instance = NginxInstance.registerPush(
                UUID.randomUUID(), "mtls-host", "mtls.internal",
                "https://mtls.internal:8443", PushTransport.MTLS,
                "a".repeat(64), null, "prod", Instant.now());

        AgentStatus expectedStatus = new AgentStatus("1.0", "1.27.5", true, "b1", true, "ok", List.of());
        when(mtlsClient.status(instance)).thenReturn(expectedStatus);

        AgentStatus status = routingClient.status(instance);

        assertThat(status).isEqualTo(expectedStatus);
        verify(mtlsClient).status(instance);
        verifyNoInteractions(tokenHttpClient);
    }

    @Test
    @DisplayName("Routes calls to tokenHttpClient when PushTransport is HTTP_TOKEN")
    void routesToTokenHttpClient() {
        NginxInstance instance = NginxInstance.registerPush(
                UUID.randomUUID(), "token-host", "token.internal",
                "http://token.internal:8080", PushTransport.HTTP_TOKEN,
                null, "enginx-sec-secret123", "prod", Instant.now());

        AgentStatus expectedStatus = new AgentStatus("1.0", "1.27.5", true, "b2", true, "ok", List.of());
        when(tokenHttpClient.status(instance)).thenReturn(expectedStatus);

        AgentStatus status = routingClient.status(instance);

        assertThat(status).isEqualTo(expectedStatus);
        verify(tokenHttpClient).status(instance);
        verifyNoInteractions(mtlsClient);
    }

    @Test
    @DisplayName("Routes calls to tokenHttpClient when PushTransport is GRPC_TOKEN")
    void routesToTokenHttpClientForGrpc() {
        NginxInstance instance = NginxInstance.registerPush(
                UUID.randomUUID(), "grpc-host", "grpc.internal",
                "https://grpc.internal:8443", PushTransport.GRPC_TOKEN,
                null, "enginx-sec-secret123", "prod", Instant.now());

        AgentStatus expectedStatus = new AgentStatus("1.0", "1.27.5", true, "b3", true, "ok", List.of());
        when(tokenHttpClient.status(instance)).thenReturn(expectedStatus);

        AgentStatus status = routingClient.status(instance);

        assertThat(status).isEqualTo(expectedStatus);
        verify(tokenHttpClient).status(instance);
        verifyNoInteractions(mtlsClient);
    }
}
