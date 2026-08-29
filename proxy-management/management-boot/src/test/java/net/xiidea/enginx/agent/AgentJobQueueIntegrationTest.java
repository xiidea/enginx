package net.xiidea.enginx.agent;

import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobPayload;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.agent.AgentJobStatus;
import net.xiidea.enginx.domain.agent.AgentJobType;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue a pull host collects from.
 *
 * <p>Most of what matters here is about a worker that is a process on another machine: it can
 * vanish between collecting a job and reporting on it, and it must never be handed two jobs that
 * would race to reconfigure the same NGINX.
 */
@Import(TestSubjectProvider.Config.class)
class AgentJobQueueIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AgentJobQueue queue;
    @Autowired
    private AgentEnrolmentService enrolment;
    @Autowired
    private TestSubjectProvider caller;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID instanceId;
    private UUID otherInstanceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate agent_jobs, agent_registration_tokens, agent_tokens, nginx_instances cascade");
        caller.actAsSuperAdmin();
        instanceId = enrol("nginx-one").id();
        otherInstanceId = enrol("nginx-two").id();
    }

    private NginxInstance enrol(String name) {
        String token = enrolment.mintRegistrationToken(name, null, null).secret();
        return enrolment.register(token, name, name + ".example.com", "TEST").instance();
    }

    private AgentJob queueStage(UUID host) {
        return queue.enqueue(host, null, AgentJobType.STAGE_BUNDLE,
                AgentJobPayload.forBundle(UUID.randomUUID(), "key-" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("a host collects its own work and nobody else's")
    void jobsAreDeliveredToTheirOwnHost() {
        AgentJob mine = queueStage(instanceId);
        queueStage(otherInstanceId);

        Optional<AgentJob> claimed = queue.claimNext(instanceId);

        assertThat(claimed).get().extracting(AgentJob::id).isEqualTo(mine.id());
        assertThat(claimed.get().status()).isEqualTo(AgentJobStatus.LEASED);
    }

    /**
     * Risk R2: two jobs in flight against one NGINX means two processes racing to swap the same
     * symlink. For a push host the dispatcher serialises that; here the queue must.
     */
    @Test
    @DisplayName("a host holding a job is offered no second one")
    void oneJobAtATimePerHost() {
        queueStage(instanceId);
        queueStage(instanceId);

        assertThat(queue.claimNext(instanceId)).isPresent();
        assertThat(queue.claimNext(instanceId)).isEmpty();

        // A different host is unaffected: the limit is per instance, not global.
        queueStage(otherInstanceId);
        assertThat(queue.claimNext(otherInstanceId)).isPresent();
    }

    @Test
    @DisplayName("nothing to do is an empty answer, not an error")
    void anIdleHostGetsNothing() {
        assertThat(queue.claimNext(instanceId)).isEmpty();
    }

    /**
     * The failure a lease exists for: an agent collects a job and dies. Without expiry the job
     * waits forever for a reply nobody is going to send, and the deployment behind it stalls.
     */
    @Test
    @DisplayName("a job whose holder never reported is handed out again")
    void anAbandonedJobReturnsToTheQueue() {
        AgentJob queued = queueStage(instanceId);
        AgentJob leased = queue.claimNext(instanceId).orElseThrow();
        assertThat(leased.attempts()).isEqualTo(1);

        expireLease(leased.id());
        assertThat(queue.releaseExpiredLeases()).isEqualTo(1);

        AgentJob again = queue.claimNext(instanceId).orElseThrow();
        assertThat(again.id()).isEqualTo(queued.id());
        assertThat(again.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a finished job is never handed out again")
    void completedWorkIsNotReoffered() {
        queueStage(instanceId);
        AgentJob leased = queue.claimNext(instanceId).orElseThrow();

        leased.succeeded(AgentJobResult.ok(), Instant.now());
        queue.save(leased);

        assertThat(queue.claimNext(instanceId)).isEmpty();
        // And an expired-lease sweep must not resurrect it either.
        assertThat(queue.releaseExpiredLeases()).isZero();
    }

    /**
     * Otherwise an activation queued behind a failed staging is still collected, and the host is
     * told to serve a bundle nobody intends it to.
     */
    @Test
    @DisplayName("work queued for a failed deployment is abandoned")
    void pendingWorkIsCancelled() {
        UUID deploymentId = insertDeployment();
        queue.enqueue(instanceId, deploymentId, AgentJobType.ACTIVATE_BUNDLE,
                AgentJobPayload.forBundle(UUID.randomUUID(), "key"));

        queue.cancelPendingFor(deploymentId, "the deployment failed earlier");

        assertThat(queue.claimNext(instanceId)).isEmpty();
    }

    /**
     * A deployment row for the job to hang off.
     *
     * <p>Inserted directly rather than driven through the service: what is under test is the
     * queue, and a job's foreign key to a real deployment is a constraint worth honouring rather
     * than working around.
     */
    private UUID insertDeployment() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into deployments (id, nginx_instance_id, trigger_type, idempotency_key, created_by) "
                + "values (?, ?, 'MANUAL', ?, 'test')", id, instanceId, id.toString());
        return id;
    }

    /** Simulates a holder that stopped reporting, without waiting out a real lease. */
    private void expireLease(UUID jobId) {
        jdbc.update("update agent_jobs set lease_expires_at = now() - interval '1 hour' where id = ?", jobId);
    }
}
