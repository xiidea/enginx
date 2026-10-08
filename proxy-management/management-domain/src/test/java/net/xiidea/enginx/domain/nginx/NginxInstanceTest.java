package net.xiidea.enginx.domain.nginx;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NginxInstanceTest {

    private static final String VALID_FINGERPRINT = "A".repeat(64);
    private static final String VALID_TOKEN = "enginx-sec-secret123";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Nested
    @DisplayName("Push mode registration")
    class PushRegistration {

        @Test
        void registersPushWithMtls() {
            NginxInstance instance = NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-01", "edge-01.internal",
                    "https://edge-01.internal:8443", PushTransport.MTLS,
                    VALID_FINGERPRINT, null, "prod", NOW);

            assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PUSH);
            assertThat(instance.pushTransport()).isEqualTo(PushTransport.MTLS);
            assertThat(instance.agentCertFingerprint()).isEqualTo(VALID_FINGERPRINT);
            assertThat(instance.agentAuthToken()).isNull();
            assertThat(instance.agentBaseUrl()).isEqualTo(URI.create("https://edge-01.internal:8443"));
        }

        @Test
        void rejectsMtlsWithoutFingerprint() {
            assertThatThrownBy(() -> NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-01", "edge-01.internal",
                    "https://edge-01.internal:8443", PushTransport.MTLS,
                    null, null, "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("The agent certificate fingerprint is required");
        }

        @Test
        void registersPushWithHttpToken() {
            NginxInstance instance = NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-02", "edge-02.internal",
                    "http://edge-02.internal:8080", PushTransport.HTTP_TOKEN,
                    null, VALID_TOKEN, "prod", NOW);

            assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PUSH);
            assertThat(instance.pushTransport()).isEqualTo(PushTransport.HTTP_TOKEN);
            assertThat(instance.agentCertFingerprint()).isNull();
            assertThat(instance.agentAuthToken()).isEqualTo(VALID_TOKEN);
            assertThat(instance.agentBaseUrl()).isEqualTo(URI.create("http://edge-02.internal:8080"));
        }

        @Test
        void registersPushWithGrpcToken() {
            NginxInstance instance = NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-03", "edge-03.internal",
                    "https://edge-03.internal:8443", PushTransport.GRPC_TOKEN,
                    null, VALID_TOKEN, "prod", NOW);

            assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PUSH);
            assertThat(instance.pushTransport()).isEqualTo(PushTransport.GRPC_TOKEN);
            assertThat(instance.agentCertFingerprint()).isNull();
            assertThat(instance.agentAuthToken()).isEqualTo(VALID_TOKEN);
        }

        @Test
        void rejectsHttpTokenWithoutAuthToken() {
            assertThatThrownBy(() -> NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-02", "edge-02.internal",
                    "http://edge-02.internal:8080", PushTransport.HTTP_TOKEN,
                    null, null, "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Agent auth token is required");
        }

        @Test
        void rejectsHttpTokenWithBlankAuthToken() {
            assertThatThrownBy(() -> NginxInstance.registerPush(
                    UUID.randomUUID(), "edge-02", "edge-02.internal",
                    "http://edge-02.internal:8080", PushTransport.HTTP_TOKEN,
                    null, "   ", "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Agent auth token is required");
        }
    }

    @Nested
    @DisplayName("Pull mode registration")
    class PullRegistration {

        @Test
        void registersPullWithNullDialFields() {
            NginxInstance instance = NginxInstance.registerPull(
                    UUID.randomUUID(), "edge-pull", "edge-pull.internal",
                    "prod", NOW);

            assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PULL);
            assertThat(instance.agentBaseUrl()).isNull();
            assertThat(instance.agentCertFingerprint()).isNull();
            assertThat(instance.agentAuthToken()).isNull();
        }
    }

    @Nested
    @DisplayName("Rehydration")
    class Rehydration {

        @Test
        void rehydratesTokenPushInstance() {
            UUID id = UUID.randomUUID();
            NginxInstance instance = NginxInstance.rehydrate(
                    id, "edge-01", "edge-01.internal",
                    URI.create("http://edge-01.internal:8080"), null,
                    ConnectivityMode.PUSH, PushTransport.HTTP_TOKEN, VALID_TOKEN,
                    "prod", InstanceStatus.ONLINE,
                    "1.27.5", "1.0.0", NOW, NOW, NOW, 1L);

            assertThat(instance.id()).isEqualTo(id);
            assertThat(instance.pushTransport()).isEqualTo(PushTransport.HTTP_TOKEN);
            assertThat(instance.agentAuthToken()).isEqualTo(VALID_TOKEN);
            assertThat(instance.agentCertFingerprint()).isNull();
        }
    }
}
