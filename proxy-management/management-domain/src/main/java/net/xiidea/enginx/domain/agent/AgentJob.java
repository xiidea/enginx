package net.xiidea.enginx.domain.agent;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One unit of work waiting for the host it belongs to.
 *
 * <p>A job is owed to exactly one host and to nobody else, which is what separates this from the
 * outbox: an outbox row is work the server owes itself and any replica may drain, while running
 * one of these anywhere but on its own host would configure the wrong machine.
 */
public final class AgentJob {

    private final UUID id;
    private final UUID nginxInstanceId;
    private final UUID deploymentId;
    private final AgentJobType type;
    private final AgentJobPayload payload;
    private AgentJobStatus status;
    private Instant leaseExpiresAt;
    private int attempts;
    private AgentJobResult result;
    private String error;
    private final Instant createdAt;
    private Instant updatedAt;

    private AgentJob(UUID id, UUID nginxInstanceId, UUID deploymentId, AgentJobType type, AgentJobPayload payload,
                     AgentJobStatus status, Instant leaseExpiresAt, int attempts, AgentJobResult result,
                     String error, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.nginxInstanceId = nginxInstanceId;
        this.deploymentId = deploymentId;
        this.type = type;
        this.payload = payload;
        this.status = status;
        this.leaseExpiresAt = leaseExpiresAt;
        this.attempts = attempts;
        this.result = result;
        this.error = error;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static AgentJob queue(UUID id, UUID nginxInstanceId, UUID deploymentId, AgentJobType type,
                                 AgentJobPayload payload, Instant now) {
        if (nginxInstanceId == null) {
            throw new ValidationException("nginxInstanceId", "A job belongs to one host");
        }
        if (type == null) {
            throw new ValidationException("type", "A job needs a type");
        }
        return new AgentJob(id, nginxInstanceId, deploymentId, type, payload, AgentJobStatus.QUEUED,
                null, 0, null, null, now, now);
    }

    public static AgentJob rehydrate(UUID id, UUID nginxInstanceId, UUID deploymentId, AgentJobType type,
                                     AgentJobPayload payload, AgentJobStatus status,
                                     Instant leaseExpiresAt, int attempts, AgentJobResult result,
                                     String error, Instant createdAt, Instant updatedAt) {
        return new AgentJob(id, nginxInstanceId, deploymentId, type, payload, status, leaseExpiresAt,
                attempts, result, error, createdAt, updatedAt);
    }

    /**
     * Hands this job to its host for a bounded time.
     *
     * <p>The bound is the point. A worker that is a process on another machine can vanish between
     * collecting a job and reporting on it, and without an expiry that job would wait forever for
     * a reply nobody is going to send.
     */
    public void lease(Duration duration, Instant now) {
        if (status != AgentJobStatus.QUEUED) {
            throw new IllegalStateException("Only a queued job can be leased, not one that is " + status);
        }
        this.status = AgentJobStatus.LEASED;
        this.leaseExpiresAt = now.plus(duration);
        this.attempts++;
        this.updatedAt = now;
    }

    /** Whether a leased job has run out of time to report. */
    public boolean leaseExpiredAt(Instant now) {
        return status == AgentJobStatus.LEASED && leaseExpiresAt != null && leaseExpiresAt.isBefore(now);
    }

    /**
     * Returns an abandoned job to the queue.
     *
     * <p>Safe to run again because every operation the agent performs is idempotent: bundles are
     * content-addressed and activation carries an idempotency key, so a job that was in fact
     * completed before the agent died replays to the same state rather than a different one.
     */
    public void releaseLease(Instant now) {
        if (status != AgentJobStatus.LEASED) {
            return;
        }
        this.status = AgentJobStatus.QUEUED;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
    }

    public void succeeded(AgentJobResult result, Instant now) {
        this.status = AgentJobStatus.SUCCEEDED;
        this.leaseExpiresAt = null;
        this.result = result;
        this.error = null;
        this.updatedAt = now;
    }

    public void failed(String error, AgentJobResult result, Instant now) {
        this.status = AgentJobStatus.FAILED;
        this.leaseExpiresAt = null;
        this.error = error;
        this.result = result;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID nginxInstanceId() {
        return nginxInstanceId;
    }

    public UUID deploymentId() {
        return deploymentId;
    }

    public AgentJobType type() {
        return type;
    }

    public AgentJobPayload payload() {
        return payload;
    }

    public AgentJobStatus status() {
        return status;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int attempts() {
        return attempts;
    }

    public AgentJobResult result() {
        return result;
    }

    public String error() {
        return error;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
