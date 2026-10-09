package net.xiidea.enginx.domain.nginx;

import net.xiidea.enginx.domain.certificate.EncryptedSecret;
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

    private static final String FINGERPRINT = "A".repeat(64);
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private static EncryptedSecret sealed(String marker) {
        return new EncryptedSecret(marker.getBytes(), new byte[]{1}, "k1", "AES-256-GCM", new byte[]{2}, null);
    }

    private static NginxInstance tokenHost() {
        return NginxInstance.registerPush(UUID.randomUUID(), "edge-token", "edge.internal",
                "http://edge.internal:8080", PushTransport.HTTP_TOKEN, null, sealed("t1"), "prod", NOW);
    }

    private static NginxInstance mtlsHost() {
        return NginxInstance.registerPush(UUID.randomUUID(), "edge-mtls", "edge.internal",
                "https://edge.internal:8443", PushTransport.MTLS, FINGERPRINT, null, "prod", NOW);
    }

    @Nested
    @DisplayName("Registering a push host")
    class Registration {

        @Test
        void mtlsPinsTheFingerprintAndHoldsNoToken() {
            NginxInstance instance = mtlsHost();

            assertThat(instance.pushTransport()).isEqualTo(PushTransport.MTLS);
            assertThat(instance.agentCertFingerprint()).isEqualTo(FINGERPRINT);
            assertThat(instance.agentToken()).isNull();
            assertThat(instance.agentBaseUrl()).isEqualTo(URI.create("https://edge.internal:8443"));
        }

        @Test
        void mtlsNeedsAFingerprint() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "https://edge.internal:8443", PushTransport.MTLS, null, null, "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("fingerprint is required");
        }

        @Test
        void mtlsRefusesAToken() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "https://edge.internal:8443", PushTransport.MTLS, FINGERPRINT, sealed("t"), "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("takes no token");
        }

        @Test
        void mtlsNeedsHttps() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "http://edge.internal:8443", PushTransport.MTLS, FINGERPRINT, null, "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("HTTPS");
        }

        @Test
        void tokenHostHoldsTheSealedTokenAndNoFingerprint() {
            NginxInstance instance = tokenHost();

            assertThat(instance.pushTransport()).isEqualTo(PushTransport.HTTP_TOKEN);
            assertThat(instance.agentCertFingerprint()).isNull();
            assertThat(instance.agentToken().kekId()).isEqualTo("k1");
        }

        @Test
        void tokenHostNeedsAToken() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "http://edge.internal:8080", PushTransport.HTTP_TOKEN, null, null, "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("token is required");
        }

        @Test
        void tokenHostRefusesAFingerprint() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "https://edge.internal:8443", PushTransport.HTTP_TOKEN, FINGERPRINT, sealed("t"), "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("no certificate to pin");
        }

        @Test
        void tokenHostCannotBeDialledOverAnUndiallableScheme() {
            assertThatThrownBy(() -> NginxInstance.registerPush(UUID.randomUUID(), "edge", "edge.internal",
                    "grpc://edge.internal:50051", PushTransport.HTTP_TOKEN, null, sealed("t"), "prod", NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("HTTP or HTTPS");
        }
    }

    @Nested
    @DisplayName("Checking a plaintext token")
    class TokenValidation {

        @Test
        void acceptsAndTrimsALongEnoughToken() {
            assertThat(NginxInstance.validAgentToken("  " + TOKEN + " ")).isEqualTo(TOKEN);
        }

        @Test
        void refusesATokenTheAgentWouldRefuse() {
            assertThatThrownBy(() -> NginxInstance.validAgentToken(TOKEN.substring(1)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("at least " + NginxInstance.MIN_AGENT_TOKEN_LENGTH);
        }

        @Test
        void refusesABlankToken() {
            assertThatThrownBy(() -> NginxInstance.validAgentToken("   "))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("Rotating credentials")
    class Rotation {

        @Test
        void aTokenHostHasNoCertificateToRotate() {
            NginxInstance instance = tokenHost();

            assertThatThrownBy(() -> instance.agentCertificateRotated(FINGERPRINT, NOW))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Rotate its token instead");
        }

        @Test
        void aTokenHostTakesANewToken() {
            NginxInstance instance = tokenHost();
            instance.observed(InstanceStatus.ONLINE, "1.27.5", "1.0.0", NOW);

            instance.agentTokenRotated(sealed("t2"), NOW.plusSeconds(60));

            assertThat(instance.agentToken().ciphertext()).isEqualTo("t2".getBytes());
            assertThat(instance.status()).isEqualTo(InstanceStatus.UNKNOWN);
        }

        @Test
        void anMtlsHostHasNoTokenToRotate() {
            NginxInstance instance = mtlsHost();

            assertThatThrownBy(() -> instance.agentTokenRotated(sealed("t"), NOW))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void aPullHostHasNoTokenToRotate() {
            NginxInstance instance = NginxInstance.registerPull(UUID.randomUUID(), "edge-pull", "pull.internal",
                    "prod", NOW);

            assertThatThrownBy(() -> instance.agentTokenRotated(sealed("t"), NOW))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void aPullHostHasNoDialFields() {
        NginxInstance instance = NginxInstance.registerPull(UUID.randomUUID(), "edge-pull", "pull.internal",
                "prod", NOW);

        assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PULL);
        assertThat(instance.agentBaseUrl()).isNull();
        assertThat(instance.agentCertFingerprint()).isNull();
        assertThat(instance.agentToken()).isNull();
    }
}
