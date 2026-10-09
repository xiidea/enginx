package net.xiidea.enginx.agent;

import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.agent.AgentJobStatus;
import net.xiidea.enginx.domain.agent.AgentJobType;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.infrastructure.acme.AgentAcmeChallengePublisher;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestAgent;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-01 on a host that calls in: the challenge travels as a job, and publishing waits for the
 * host to say it is in place before the authority is asked to look.
 */
@Import({TestSubjectProvider.Config.class, TestAgent.Config.class})
class PullAcmeChallengeIntegrationTest extends AbstractIntegrationTest {

    private static final String TOKEN = "evaGxfADs6pSRb2LAv9IZf17Dt3juxGJ-PCt92wr-oA";

    @Autowired
    private AgentJobQueue queue;
    @Autowired
    private AgentEnrolmentService enrolment;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private NginxAgentPort agent;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate agent_jobs, agent_tokens, agent_registration_tokens, deployments, "
                + "config_bundles, outbox_messages, proxy_sites, nginx_instances restart identity cascade");
        caller.actAsSuperAdmin();
        String token = enrolment.mintRegistrationToken("test", null, null).secret();
        instanceId = enrolment.register(token, "nginx-pull", "pull.example.com", "TEST").instance().id();
    }

    private AgentAcmeChallengePublisher publisher(Duration timeout) {
        return new AgentAcmeChallengePublisher(instances, agent, queue, timeout);
    }

    /** Plays the host: collects its next job and reports it done. */
    private AgentJob actAsHost(AgentJobType expected) {
        Instant giveUp = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(giveUp)) {
            Optional<AgentJob> claimed = queue.claimNext(instanceId);
            if (claimed.isPresent()) {
                AgentJob job = claimed.get();
                assertThat(job.type()).isEqualTo(expected);
                job.succeeded(new AgentJobResult(true, false, null, null, null, false, false, null), Instant.now());
                return queue.save(job);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("No " + expected + " job appeared");
    }

    @Test
    @DisplayName("publishing reaches a pull host as a job, and completes once the host confirms")
    void publishWaitsForThePullHost() {
        CompletableFuture<AgentJob> host = CompletableFuture.supplyAsync(
                () -> actAsHost(AgentJobType.PUBLISH_ACME_CHALLENGE));

        publisher(Duration.ofSeconds(10)).publish(TOKEN, TOKEN + ".thumbprint");

        AgentJob job = host.join();
        assertThat(job.payload().acmeToken()).isEqualTo(TOKEN);
        assertThat(job.payload().acmeAuthorization()).isEqualTo(TOKEN + ".thumbprint");
        assertThat(queue.find(job.id()).orElseThrow().status()).isEqualTo(AgentJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a pull host that never confirms is not counted, so a lone silent host fails the publish")
    void anUnconfirmedHostIsNotCounted() {
        assertThatThrownBy(() -> publisher(Duration.ofMillis(600)).publish(TOKEN, TOKEN + ".thumbprint"))
                .isInstanceOf(CertificateIssuanceException.class)
                .hasMessageContaining("could not be published to any");
    }

    @Test
    @DisplayName("withdrawing queues a removal for the pull host without waiting")
    void withdrawQueuesARemoval() {
        publisher(Duration.ofSeconds(1)).withdraw(TOKEN);

        List<String> jobs = jdbc.queryForList(
                "select type || ' ' || (payload->>'acmeToken') from agent_jobs where nginx_instance_id = ?",
                String.class, instanceId);
        assertThat(jobs).containsExactly("REMOVE_ACME_CHALLENGE " + TOKEN);
    }
}
