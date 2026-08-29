package net.xiidea.enginx.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real schema.
 *
 * <p>PostgreSQL rather than an in-memory substitute, because the parts of this system most worth
 * testing are the parts an embedded database does not reproduce: partial indexes, check
 * constraints, {@code FOR UPDATE SKIP LOCKED}, prefix matching on a reversed domain, the
 * append-only trigger on the audit table, and Quartz's own clustered schema.
 */
@SpringBootTest(properties = {
        // The scheduler is switched off for most tests and the jobs are invoked directly.
        // Leaving it running would make assertions race a background sweep: a test that expects
        // an outbox row to still be NEW would sometimes find it already dispatched.
        // QuartzSchedulerIntegrationTest turns it back on and exercises it deliberately.
        "enginx.scheduler.enabled=false",
        // Certificate private keys are encrypted at rest, and the platform refuses to start
        // without a key. A fixed one here keeps the ciphertext assertions reproducible.
        "enginx.crypto.active-key-id=test",
        "enginx.crypto.keys.test=ZW5naW54LWludGVncmF0aW9uLXRlc3Qta2V5LTMyYiE=",
        // Never reach a real authority from a test, whatever else goes wrong.
        "enginx.acme.directory-url=http://localhost:1/dir",
        // No identity provider. Building the OIDC decoder performs discovery, which is a network
        // call these tests have no reason to make -- and one that would make the whole suite
        // depend on a Keycloak container being up. The local issuer needs no network at all.
        // Token decoding is mocked below regardless; this only decides what the context builds.
        "enginx.auth.oidc-enabled=false",
        "enginx.auth.local-enabled=true",
        "enginx.auth.jwt-secret=an-integration-test-signing-secret-long-enough-for-hs256"
})
public abstract class AbstractIntegrationTest {

    /**
     * One container for the whole test run.
     *
     * <p>Started here rather than through the {@code @Testcontainers} extension, which stops a
     * static container when its test class finishes. The Spring context is cached and reused
     * across classes, so the second class would then hold a connection pool pointing at a port
     * that no longer exists. Starting it once and letting Ryuk reap it at JVM exit keeps the
     * container's lifetime matched to the context cache's.
     */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("enginx")
            .withUsername("enginx")
            .withPassword("enginx");

    static {
        POSTGRES.start();
    }

    /**
     * Replaces the routing decoder outright. These tests drive
     * the application services directly and inject the caller through {@link TestSubjectProvider},
     * which is the same port the production security layer implements.
     */
    @MockitoBean
    JwtDecoder jwtDecoder;
}
