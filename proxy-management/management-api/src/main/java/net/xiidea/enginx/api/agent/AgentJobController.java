package net.xiidea.enginx.api.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import net.xiidea.enginx.application.agent.AgentAuthenticationService;
import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.application.deployment.PullDeploymentCoordinator;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobResult;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * How a pull host collects its work and reports on it.
 *
 * <p>Long-polling rather than a fixed interval, so a queued job reaches its host in about as long
 * as the request takes rather than waiting out a poll. That matters most for an ACME challenge,
 * which has seconds to appear, but it also means a deployment feels immediate instead of arriving
 * whenever the next tick happens to land.
 */
@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Agents", description = "Enrolment and reporting for hosts that call the platform")
public class AgentJobController {

    private static final Logger log = LoggerFactory.getLogger(AgentJobController.class);

    /** How often a parked request re-checks. Cheap: one indexed lookup for one instance. */
    private static final Duration RECHECK = Duration.ofMillis(500);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);

    private final AgentAuthenticationService authentication;
    private final AgentJobQueue queue;
    private final PullDeploymentCoordinator deployments;
    private final ConfigBundleRepository bundles;
    private final Duration defaultWait;

    /**
     * Wakes parked requests.
     *
     * <p>One scheduler for the whole application rather than a thread per waiting agent: a request
     * parked on {@code DeferredResult} holds no servlet thread, and this must not reintroduce the
     * per-agent thread that would defeat that.
     */
    private final ScheduledExecutorService waiters =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("agent-poll-", 0).factory());

    public AgentJobController(AgentAuthenticationService authentication, AgentJobQueue queue,
                              PullDeploymentCoordinator deployments, ConfigBundleRepository bundles,
                              @Value("${enginx.agent.poll-wait:30s}") Duration defaultWait) {
        this.authentication = authentication;
        this.queue = queue;
        this.deployments = deployments;
        this.bundles = bundles;
        this.defaultWait = defaultWait;
    }

    /**
     * Asks for the next job, waiting for one to appear.
     *
     * <p>Returns {@code DeferredResult}, which releases the servlet thread while the request is
     * parked. A blocking sleep here would look identical and be a slow outage: sixty agents each
     * holding a thread for thirty seconds exhausts the default pool, and the console stops
     * responding for reasons nobody would connect to agents polling.
     */
    @GetMapping("/jobs/request")
    @Operation(summary = "Collect the next job for this host",
            description = "Holds the request open for up to waitSeconds until work appears. "
                    + "204 means there was nothing to do, which is the ordinary case.")
    public DeferredResult<ResponseEntity<JobResponse>> request(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            // Seconds, not an ISO-8601 duration. A Duration parameter would need "PT30S" in the
            // query string, which is an odd thing to ask of an agent and easy to get wrong in a
            // way that only shows up as a 400 at runtime.
            @RequestParam(name = "waitSeconds", required = false) Integer waitSeconds) {

        NginxInstance instance = requireAgent(authorization);
        Duration limit = clamp(waitSeconds == null ? defaultWait : Duration.ofSeconds(waitSeconds));

        // A little beyond the wait, so the answer below always wins the race with the container's
        // own timeout and an agent never sees a 503 it would have to interpret.
        DeferredResult<ResponseEntity<JobResponse>> result =
                new DeferredResult<>(limit.plusSeconds(5).toMillis(),
                        () -> ResponseEntity.noContent().build());

        poll(instance.id(), result, Instant.now().plus(limit));
        return result;
    }

    private void poll(UUID instanceId, DeferredResult<ResponseEntity<JobResponse>> result, Instant deadline) {
        if (result.isSetOrExpired()) {
            return;
        }
        Optional<AgentJob> claimed = queue.claimNext(instanceId);
        if (claimed.isPresent()) {
            result.setResult(ResponseEntity.ok(JobResponse.of(claimed.get())));
            return;
        }
        if (Instant.now().isAfter(deadline)) {
            result.setResult(ResponseEntity.noContent().build());
            return;
        }
        waiters.schedule(() -> poll(instanceId, result, deadline), RECHECK.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PostMapping("/jobs/{id}/result")
    @Operation(summary = "Report how a job went",
            description = "Advances the deployment the job belongs to. A host may only report on "
                    + "its own jobs.")
    public ResponseEntity<Void> report(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID id,
            @Valid @RequestBody ResultRequest request) {

        NginxInstance instance = requireAgent(authorization);
        AgentJob job = queue.find(id)
                .filter(candidate -> candidate.nginxInstanceId().equals(instance.id()))
                .orElseThrow(() -> new NotFoundException("AGENT_JOB", id));

        AgentJobResult result = request.toDomain();
        if (result.succeeded()) {
            job.succeeded(result, Instant.now());
        } else {
            job.failed(result.error(), result, Instant.now());
        }
        queue.save(job);

        deployments.onResult(job, result);
        log.debug("Instance {} reported {} on {}", instance.name(), result.succeeded(), job.type());
        return ResponseEntity.noContent().build();
    }

    /**
     * The bundle a job refers to.
     *
     * <p>The most important authorisation check in this feature. A bundle carries every site's
     * private key, so it is not enough that the caller holds a valid agent token: the bundle must
     * belong to <em>that</em> caller's host. Without this, any enrolled host could read the
     * configuration and keys of every other.
     */
    @GetMapping("/bundles/{bundleId}")
    @Operation(summary = "Fetch a bundle this host has been told to apply",
            description = "Refused unless the bundle belongs to the calling host.")
    public BundleResponse bundle(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID bundleId) {

        NginxInstance instance = requireAgent(authorization);
        ConfigBundle bundle = bundles.findById(bundleId)
                .orElseThrow(() -> new NotFoundException("CONFIG_BUNDLE", bundleId));

        if (!bundle.nginxInstanceId().equals(instance.id())) {
            // Refused as forbidden rather than not-found: the caller is a known host asking for
            // something real that is not its own, which is worth being unambiguous about in a log.
            log.warn("Instance {} asked for bundle {}, which belongs to another host",
                    instance.name(), bundleId);
            throw new AccessDeniedException("That bundle belongs to another host");
        }

        return new BundleResponse(bundle.id(), bundle.sequence(), bundle.contentHash(),
                bundle.files().stream()
                        .map(file -> new BundleFileResponse(file.path(), file.content(), file.sha256(),
                                file.sensitive(), file.sensitive() ? "0600" : null))
                        .toList());
    }

    private Duration clamp(Duration requested) {
        if (requested.isNegative() || requested.isZero()) {
            return Duration.ZERO;
        }
        return requested.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : requested;
    }

    private NginxInstance requireAgent(String authorization) {
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : null;

        return authentication.authenticate(token)
                .orElseThrow(() -> new AccessDeniedException("That agent token is not recognised"));
    }

    @Schema(name = "AgentJobResponse", requiredProperties = {"jobId", "type", "bundleId", "idempotencyKey"})
    public record JobResponse(UUID jobId, String type, UUID bundleId, String idempotencyKey, boolean reload,
                              String acmeToken, String acmeAuthorization) {

        static JobResponse of(AgentJob job) {
            return new JobResponse(job.id(), job.type().name(), job.payload().bundleId(),
                    job.payload().idempotencyKey(), job.payload().reload(),
                    job.payload().acmeToken(), job.payload().acmeAuthorization());
        }
    }

    /** @param validationFailed nginx -t rejected it, which is never retried and never a 5xx */
    @Schema(name = "AgentJobResultRequest", requiredProperties = {"succeeded"})
    public record ResultRequest(Boolean succeeded, Boolean validationFailed, String testOutput,
                                String nginxVersion, String previousBundleId, Boolean noop,
                                Boolean rolledBack, String error) {

        AgentJobResult toDomain() {
            return new AgentJobResult(Boolean.TRUE.equals(succeeded), Boolean.TRUE.equals(validationFailed),
                    testOutput, nginxVersion, previousBundleId, Boolean.TRUE.equals(noop),
                    Boolean.TRUE.equals(rolledBack), error);
        }
    }

    @Schema(name = "AgentBundleResponse", requiredProperties = {"bundleId", "sequence", "contentHash", "files"})
    public record BundleResponse(UUID bundleId, long sequence, String contentHash,
                                 List<BundleFileResponse> files) {
    }

    /** @param mode 0600 for key material, so it is never written world-readable on the host */
    @Schema(name = "AgentBundleFileResponse", requiredProperties = {"path", "content", "sha256"})
    public record BundleFileResponse(String path, String content, String sha256, boolean sensitive,
                                     String mode) {
    }
}
