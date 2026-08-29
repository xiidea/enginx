package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One attempt to make an NGINX instance serve a particular bundle.
 *
 * <p>The aggregate owns its own state machine so that "reload without a successful validation" is
 * not merely discouraged but unrepresentable: {@link #reloaded} refuses unless validation was
 * recorded first.
 */
public final class Deployment {

    private final UUID id;
    private final UUID nginxInstanceId;
    private UUID configBundleId;
    private UUID previousBundleId;
    private final DeploymentTrigger trigger;
    private final String idempotencyKey;
    private final String createdBy;
    private final Instant createdAt;

    private DeploymentStatus status;
    private int attempt;
    private String nginxTestOutput;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private boolean validated;

    private final List<DeploymentEvent> events = new ArrayList<>();

    private Deployment(UUID id, UUID nginxInstanceId, UUID configBundleId, UUID previousBundleId,
                       DeploymentTrigger trigger, String idempotencyKey, DeploymentStatus status,
                       int attempt, String nginxTestOutput, String errorMessage, Instant startedAt,
                       Instant finishedAt, String createdBy, Instant createdAt) {
        this.id = id;
        this.nginxInstanceId = nginxInstanceId;
        this.configBundleId = configBundleId;
        this.previousBundleId = previousBundleId;
        this.trigger = trigger;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.attempt = attempt;
        this.nginxTestOutput = nginxTestOutput;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /**
     * Queues a deployment without a bundle.
     *
     * <p>Rendering happens at dispatch, not here. Building the bundle when the request arrives
     * would capture the state the requester saw, and two edits made seconds apart would produce
     * two snapshots whose application order decides the outcome — applying the earlier one second
     * would silently revert the later edit (architecture risk R2).
     */
    public static Deployment queue(UUID id, UUID nginxInstanceId, DeploymentTrigger trigger,
                                   String createdBy, Instant now) {
        if (nginxInstanceId == null) {
            throw new ValidationException("nginxInstanceId", "A deployment targets an NGINX instance");
        }
        // The key is the deployment id. Every retry of this deployment therefore reuses it, so a
        // response lost in flight cannot cause the agent to apply the same bundle twice.
        return new Deployment(id, nginxInstanceId, null, null, trigger,
                id.toString(), DeploymentStatus.PENDING, 0, null, null, null, null, createdBy, now);
    }

    /** Queues a re-activation of a bundle that has already been validated on this host. */
    public static Deployment queueRollback(UUID id, UUID nginxInstanceId, UUID targetBundleId,
                                           UUID currentBundleId, String createdBy, Instant now) {
        if (targetBundleId == null) {
            throw new ValidationException("configBundleId", "A rollback needs a target bundle");
        }
        return new Deployment(id, nginxInstanceId, targetBundleId, currentBundleId,
                DeploymentTrigger.ROLLBACK, id.toString(), DeploymentStatus.PENDING, 0,
                null, null, null, null, createdBy, now);
    }

    /** Records what the dispatcher rendered, and what it will replace. */
    public void bundleRendered(UUID renderedBundleId, UUID currentBundleId, Instant now) {
        this.configBundleId = renderedBundleId;
        this.previousBundleId = currentBundleId;
        record(DeploymentPhase.RENDER, DeploymentEvent.EventResult.SUCCESS, null, now);
    }

    public static Deployment rehydrate(UUID id, UUID nginxInstanceId, UUID configBundleId, UUID previousBundleId,
                                       DeploymentTrigger trigger, String idempotencyKey, DeploymentStatus status,
                                       int attempt, String nginxTestOutput, String errorMessage, Instant startedAt,
                                       Instant finishedAt, String createdBy, Instant createdAt,
                                       List<DeploymentEvent> events) {
        Deployment deployment = new Deployment(id, nginxInstanceId, configBundleId, previousBundleId, trigger,
                idempotencyKey, status, attempt, nginxTestOutput, errorMessage, startedAt, finishedAt,
                createdBy, createdAt);
        if (events != null) {
            deployment.events.addAll(events);
        }
        deployment.validated = events != null && events.stream()
                .anyMatch(e -> e.phase() == DeploymentPhase.VALIDATE
                        && e.result() == DeploymentEvent.EventResult.SUCCESS);
        return deployment;
    }

    public void started(Instant now) {
        if (status.isTerminal()) {
            throw new ConflictException("Deployment " + id + " has already finished");
        }
        if (configBundleId == null) {
            throw new ConflictException("Deployment " + id + " has no rendered bundle to apply");
        }
        this.status = DeploymentStatus.IN_PROGRESS;
        this.attempt++;
        this.startedAt = now;
        record(DeploymentPhase.UPLOAD, DeploymentEvent.EventResult.STARTED, "attempt " + attempt, now);
    }

    public void uploaded(Instant now) {
        record(DeploymentPhase.UPLOAD, DeploymentEvent.EventResult.SUCCESS, null, now);
    }

    public void validated(String testOutput, Instant now) {
        this.validated = true;
        this.nginxTestOutput = testOutput;
        record(DeploymentPhase.VALIDATE, DeploymentEvent.EventResult.SUCCESS, testOutput, now);
    }

    public void activated(Instant now) {
        requireValidated("activate");
        record(DeploymentPhase.ACTIVATE, DeploymentEvent.EventResult.SUCCESS, null, now);
    }

    /**
     * NGINX was reloaded. Refuses unless validation succeeded first, so the rule that a reload
     * never follows a failed check is a property of the model rather than of the calling order.
     */
    public void reloaded(String nginxVersion, Instant now) {
        requireValidated("reload");
        record(DeploymentPhase.RELOAD, DeploymentEvent.EventResult.SUCCESS, nginxVersion, now);
    }

    public void succeeded(Instant now) {
        this.status = DeploymentStatus.SUCCESS;
        this.finishedAt = now;
        this.errorMessage = null;
    }

    public void failed(DeploymentPhase phase, String error, String testOutput, Instant now) {
        this.status = DeploymentStatus.FAILED;
        this.finishedAt = now;
        this.errorMessage = error;
        if (testOutput != null) {
            this.nginxTestOutput = testOutput;
        }
        record(phase, DeploymentEvent.EventResult.FAILURE, error, now);
    }

    /** A retryable failure: the deployment stays pending for another attempt. */
    public void deferred(DeploymentPhase phase, String error, Instant now) {
        this.status = DeploymentStatus.PENDING;
        this.errorMessage = error;
        record(phase, DeploymentEvent.EventResult.FAILURE, error, now);
    }

    public void skipped(DeploymentPhase phase, String reason, Instant now) {
        record(phase, DeploymentEvent.EventResult.SKIPPED, reason, now);
    }

    /**
     * Records what the host reported when asked whether it now serves each name.
     *
     * <p>Never changes {@code status}. A deployment that reloaded successfully succeeded: the
     * configuration is loaded and being served. A verification failure is usually an upstream
     * that is down or a DNS name that does not point here, and reverting the configuration would
     * fix neither while discarding the operator's change. R3 is explicit that this must be
     * surfaced rather than acted on, and this method is where that rule lives.
     */
    public void verified(List<SiteVerification> results, Instant now) {
        if (results.isEmpty()) {
            return;
        }
        List<SiteVerification> unanswered = results.stream().filter(r -> !r.responded()).toList();

        String detail = results.stream().map(SiteVerification::describe)
                .collect(java.util.stream.Collectors.joining("\n"));

        record(DeploymentPhase.VERIFY,
                unanswered.isEmpty() ? DeploymentEvent.EventResult.SUCCESS : DeploymentEvent.EventResult.FAILURE,
                detail, now);
    }

    /**
     * The host could not be asked. Recorded as skipped rather than failed: not knowing whether a
     * site answers is a different thing from knowing that it does not, and conflating them would
     * make an agent restart look like an outage.
     */
    public void verificationSkipped(String reason, Instant now) {
        record(DeploymentPhase.VERIFY, DeploymentEvent.EventResult.SKIPPED, reason, now);
    }

    public void agentRolledBack(String detail, Instant now) {
        record(DeploymentPhase.ROLLBACK, DeploymentEvent.EventResult.SUCCESS, detail, now);
    }

    private void requireValidated(String action) {
        if (!validated) {
            throw new ConflictException(
                    "Refusing to " + action + " deployment " + id + ": configuration validation has not succeeded");
        }
    }

    private void record(DeploymentPhase phase, DeploymentEvent.EventResult result, String detail, Instant now) {
        events.add(DeploymentEvent.of(id, phase, result, detail, now));
    }

    public UUID id() {
        return id;
    }

    public UUID nginxInstanceId() {
        return nginxInstanceId;
    }

    public UUID configBundleId() {
        return configBundleId;
    }

    public UUID previousBundleId() {
        return previousBundleId;
    }

    public DeploymentTrigger trigger() {
        return trigger;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public DeploymentStatus status() {
        return status;
    }

    public int attempt() {
        return attempt;
    }

    public String nginxTestOutput() {
        return nginxTestOutput;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<DeploymentEvent> events() {
        return List.copyOf(events);
    }
}
