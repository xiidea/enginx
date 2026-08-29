package net.xiidea.enginx.identity;

import net.xiidea.enginx.application.agent.AgentAuthenticationService;
import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.domain.nginx.ConnectivityMode;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.shared.ValidationException;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A host enrolling itself, so the platform never has to dial it.
 *
 * <p>The registration token is the whole of a new host's claim, and a host in the estate receives
 * configuration bundles carrying every site's private key. Most of what follows is about the
 * bounds on that token rather than the happy path.
 */
@Import(TestSubjectProvider.Config.class)
class AgentEnrolmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AgentEnrolmentService enrolment;
    @Autowired
    private AgentAuthenticationService authentication;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate agent_registration_tokens, agent_tokens, nginx_instances cascade");
        caller.actAsSuperAdmin();
    }

    private String mint() {
        return enrolment.mintRegistrationToken("test", null, null).secret();
    }

    @Test
    @DisplayName("a host enrols itself and becomes a pull instance with nothing to dial")
    void enrolmentCreatesAPullInstance() {
        AgentEnrolmentService.Enrolled enrolled =
                enrolment.register(mint(), "nginx-edge-01", "edge01.example.com", "PRODUCTION");

        NginxInstance instance = enrolled.instance();
        assertThat(instance.connectivityMode()).isEqualTo(ConnectivityMode.PULL);
        // Not merely unknown: a pull host is never dialled, so a URL or a pinned fingerprint here
        // would be a field that looks like it means something and does not.
        assertThat(instance.agentBaseUrl()).isNull();
        assertThat(instance.agentCertFingerprint()).isNull();
        assertThat(enrolled.secret()).startsWith("enginx-agt-");
    }

    @Test
    @DisplayName("the issued token identifies exactly its own host")
    void agentTokenResolvesToItsInstance() {
        AgentEnrolmentService.Enrolled first =
                enrolment.register(mint(), "nginx-a", "a.example.com", "TEST");
        AgentEnrolmentService.Enrolled second =
                enrolment.register(mint(), "nginx-b", "b.example.com", "TEST");

        assertThat(authentication.authenticate(first.secret()))
                .get().extracting(NginxInstance::id).isEqualTo(first.instance().id());
        assertThat(authentication.authenticate(second.secret()))
                .get().extracting(NginxInstance::id).isEqualTo(second.instance().id());
        assertThat(authentication.authenticate("enginx-agt-not-a-real-token")).isEmpty();
    }

    /**
     * The token travels — into a manifest, a provisioning script, a chat message. The useful
     * question is not whether it leaks but how long a leaked one is worth anything.
     */
    @Test
    @DisplayName("a spent token cannot enrol a second host")
    void useCountIsEnforced() {
        String token = enrolment.mintRegistrationToken("single use", null, 1).secret();
        enrolment.register(token, "nginx-first", "first.example.com", "TEST");

        assertThatThrownBy(() -> enrolment.register(token, "nginx-second", "second.example.com", "TEST"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an expired token cannot enrol")
    void expiryIsEnforced() {
        assertThatThrownBy(() -> enrolment.mintRegistrationToken("already gone",
                Instant.now().minus(1, ChronoUnit.HOURS), null))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * Revocation stops further enrolment and nothing else. A host already using the platform holds
     * a credential of its own, and taking that away because the token it arrived with was retired
     * would be a surprise outage.
     */
    @Test
    @DisplayName("revoking a registration token leaves hosts it already enrolled working")
    void revocationDoesNotStrandEnrolledHosts() {
        AgentEnrolmentService.Minted minted = enrolment.mintRegistrationToken("to revoke", null, null);
        AgentEnrolmentService.Enrolled enrolled =
                enrolment.register(minted.secret(), "nginx-live", "live.example.com", "TEST");

        enrolment.revokeRegistrationToken(minted.token().id());

        assertThatThrownBy(() -> enrolment.register(minted.secret(), "nginx-next", "next.example.com", "TEST"))
                .isInstanceOf(ValidationException.class);
        assertThat(authentication.authenticate(enrolled.secret())).isPresent();
    }

    @Test
    @DisplayName("revoking a host's own token stops it collecting work")
    void agentTokenCanBeRevoked() {
        AgentEnrolmentService.Enrolled enrolled =
                enrolment.register(mint(), "nginx-gone", "gone.example.com", "TEST");

        enrolment.revokeAgentToken(enrolled.instance().id());

        assertThat(authentication.authenticate(enrolled.secret())).isEmpty();
    }

    /**
     * The token is never stored, only its digest — so a database dump does not hand over the
     * estate, and a lost token is replaced rather than recovered.
     */
    @Test
    @DisplayName("neither token is stored in clear")
    void tokensAreStoredAsDigests() {
        AgentEnrolmentService.Minted minted = enrolment.mintRegistrationToken("digest check", null, null);
        AgentEnrolmentService.Enrolled enrolled =
                enrolment.register(minted.secret(), "nginx-digest", "digest.example.com", "TEST");

        assertThat(jdbc.queryForObject(
                "select count(*) from agent_registration_tokens where token_hash = ?", Integer.class,
                minted.secret())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from agent_tokens where token_hash = ?", Integer.class,
                enrolled.secret())).isZero();

        // And what is stored is a digest, not a truncation or an encoding of the original.
        String stored = jdbc.queryForObject(
                "select token_hash from agent_tokens", String.class);
        assertThat(stored).hasSize(64).doesNotContain(enrolled.secret());
    }

    /**
     * A pull host has no certificate to pin, so offering to rotate one would be a control that
     * silently does nothing.
     */
    @Test
    @DisplayName("a pull host refuses certificate rotation rather than pretending to accept it")
    void certificateRotationIsMeaninglessForAPullHost() {
        NginxInstance instance = enrolment.register(mint(), "nginx-pull", "pull.example.com", "TEST")
                .instance();

        assertThatThrownBy(() -> instance.agentCertificateRotated("B".repeat(64), Instant.now()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agent token");
    }
}
