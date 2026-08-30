package net.xiidea.enginx.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client cannot escape the login rate limit by forging {@code X-Forwarded-For}.
 *
 * <p>This is VA-1. Spring's {@code ForwardedHeaderFilter} (the old {@code framework} strategy)
 * believed {@code X-Forwarded-For} from anyone, so rotating it handed out a fresh rate-limit
 * bucket per request and defeated the only defence the unauthenticated login endpoint has. The
 * fix routes forwarded headers through Tomcat's {@code RemoteIpValve}, which trusts them only from
 * an address in {@code internal-proxies}.
 *
 * <p>Here {@code internal-proxies} is emptied, standing in for a deployment whose edge is not
 * configured, or a client connecting directly. The loopback test client is therefore untrusted,
 * its {@code X-Forwarded-For} is ignored, every request keys on the same real peer, and the limit
 * holds no matter what the header says. A real embedded server, not MockMvc, because the valve is
 * a container component and MockMvc never runs it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "enginx.scheduler.enabled=false",
                "enginx.auth.oidc-enabled=false",
                "enginx.auth.local-enabled=true",
                "enginx.auth.jwt-secret=an-integration-test-signing-secret-long-enough-for-hs256",
                "enginx.crypto.active-key-id=test",
                "enginx.crypto.keys.test=ZW5naW54LWludGVncmF0aW9uLXRlc3Qta2V5LTMyYiE=",
                "enginx.acme.directory-url=http://localhost:1/dir",
                "enginx.rate-limit.enabled=true",
                "enginx.rate-limit.sensitive-capacity=10",
                // The point of the test: trust no proxy, so the loopback client is not one and its
                // X-Forwarded-For carries no weight.
                "server.tomcat.remoteip.internal-proxies="
        })
class ForwardedForRateLimitTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("enginx").withUsername("enginx").withPassword("enginx");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    private int port;

    @Test
    void rotatingForwardedForDoesNotHandOutFreshBuckets() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        int limit = 10;
        boolean throttled = false;

        // A handful beyond the limit, each with a different forged client address. Without the fix
        // every one of these is a 401 (a fresh bucket per address); with it the bucket is shared
        // and a 429 appears once the ten are spent.
        for (int i = 0; i < limit + 6; i++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throttled = true;
                break;
            }
            // Every non-throttled attempt is a plain auth failure, never a fresh success.
            assertThat(response.statusCode()).isEqualTo(401);
        }

        assertThat(throttled)
                .as("rotating X-Forwarded-For should not escape the login rate limit")
                .isTrue();
    }
}
