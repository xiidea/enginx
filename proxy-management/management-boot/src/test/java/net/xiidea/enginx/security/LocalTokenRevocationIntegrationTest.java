package net.xiidea.enginx.security;

import net.xiidea.enginx.application.identity.AccessTokenIssuer;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.domain.identity.LocalUserRepository;
import net.xiidea.enginx.domain.permission.GlobalRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local token stops working the moment its account is revoked, not when it happens to expire.
 *
 * <p>This is VA-3. Real signing and real decoding over a real port -- no mocked {@code JwtDecoder}
 * -- so the token travels the same path a caller's would and meets
 * {@link net.xiidea.enginx.security.local.LocalTokenRevocationFilter} exactly as production does.
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
                "enginx.acme.directory-url=http://localhost:1/dir"
        })
class LocalTokenRevocationIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("enginx").withUsername("enginx").withPassword("enginx");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    private int port;
    @Autowired
    private LocalUserRepository users;
    @Autowired
    private AccessTokenIssuer issuer;

    private final HttpClient client = HttpClient.newHttpClient();

    private LocalUser newAdmin() {
        LocalUser user = LocalUser.create(UUID.randomUUID(), "admin" + System.nanoTime(),
                "$2a$12$notarealhashbutnonblankvaluexxxxxxxxxxxxxxxxxxxxxxxxx",
                "admin@example.com", "Admin", Set.of(GlobalRole.SUPER_ADMIN), Set.of(),
                false, "test", Instant.now());
        return users.save(user);
    }

    private int getInstances(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/nginx-instances"))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aTokenWorksUntilItsAccountIsDisabled() throws Exception {
        LocalUser user = newAdmin();
        String token = issuer.issue(user).token();

        // Good while the account is live.
        assertThat(getInstances(token)).isEqualTo(200);

        // Disabled after the token was issued.
        user.setEnabled(false, Instant.now());
        users.save(user);

        // The same token, now refused -- without waiting out its lifetime.
        assertThat(getInstances(token)).isEqualTo(401);
    }

    @Test
    void aTokenIssuedBeforeARoleChangeStopsWorking() throws Exception {
        LocalUser user = newAdmin();
        String token = issuer.issue(user).token();

        assertThat(getInstances(token)).isEqualTo(200);

        // A change to the account -- here its profile/roles -- moves the revocation epoch past the
        // token's issue time. Dated an hour on so the shift is unambiguous against the grace window.
        user.updateProfile("admin@example.com", "Admin", Set.of(GlobalRole.OPERATOR), Set.of(),
                Instant.now().plusSeconds(3600));
        users.save(user);

        assertThat(getInstances(token)).isEqualTo(401);
    }
}
