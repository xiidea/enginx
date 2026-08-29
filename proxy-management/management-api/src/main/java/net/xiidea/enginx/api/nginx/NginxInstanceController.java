package net.xiidea.enginx.api.nginx;

import net.xiidea.enginx.api.nginx.dto.NginxInstanceResponse;
import net.xiidea.enginx.api.nginx.dto.RotateAgentCertificateRequest;
import net.xiidea.enginx.api.nginx.dto.RegisterNginxInstanceRequest;
import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.application.nginx.NginxInstanceService;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/nginx-instances")
@Tag(name = "NGINX instances", description = "Managed NGINX hosts")
public class NginxInstanceController {

    private final NginxInstanceService service;
    private final AgentJobQueue jobs;

    public NginxInstanceController(NginxInstanceService service, AgentJobQueue jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @GetMapping
    @Operation(summary = "List managed NGINX instances")
    public List<NginxInstanceResponse> list() {
        return service.findAll().stream().map(NginxInstanceController::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one NGINX instance")
    public NginxInstanceResponse get(@PathVariable UUID id) {
        return toResponse(service.get(id));
    }

    @PutMapping("/{id}/agent-certificate")
    @Operation(summary = "Trust a new agent certificate for this host",
            description = "The fingerprint is pinned in addition to CA verification, so replacing an "
                    + "agent's certificate makes the host unreachable until this is called. Swap the "
                    + "certificate on the host first, then call this: the window between the two is a "
                    + "loss of control, never of traffic — the host keeps serving throughout.")
    public NginxInstanceResponse rotateAgentCertificate(
            @PathVariable UUID id,
            @Valid @RequestBody RotateAgentCertificateRequest request) {
        return toResponse(service.rotateAgentCertificate(id, request.agentCertFingerprint()));
    }

    @PostMapping
    @Operation(summary = "Register an NGINX instance",
            description = "Restricted to SUPER_ADMIN: registering a host decides where configuration is deployed.")
    public ResponseEntity<NginxInstanceResponse> register(@Valid @RequestBody RegisterNginxInstanceRequest request,
                                                          UriComponentsBuilder uriBuilder) {
        NginxInstance instance = service.register(request.name(), request.hostname(), request.agentBaseUrl(),
                request.agentCertFingerprint(), request.environment());

        URI location = uriBuilder.path("/api/v1/nginx-instances/{id}").buildAndExpand(instance.id()).toUri();
        return ResponseEntity.created(location).body(toResponse(instance));
    }

    /**
     * What this host has been asked to do lately.
     *
     * <p>Only meaningful for a host that collects its own work. For a push host the platform makes
     * the call itself and the deployment record already says what happened; here the deployment
     * says only that it is waiting, and this says what for.
     */
    @GetMapping("/{id}/agent-jobs")
    @Operation(summary = "Recent work queued for this host",
            description = "Empty for a host the platform dials, which is told what to do rather "
                    + "than collecting it.")
    public List<AgentJobResponse> agentJobs(@PathVariable UUID id,
                                            @RequestParam(defaultValue = "20") int limit) {
        // Authorises the read, and 404s a host that does not exist rather than returning an
        // empty list that looks like a host with nothing to do.
        service.get(id);

        return jobs.recentFor(id, limit).stream()
                .map(job -> new AgentJobResponse(job.id(), job.type().name(), job.status().name(),
                        job.attempts(), job.deploymentId(), job.payload().bundleId(),
                        job.leaseExpiresAt(), job.error(), job.createdAt(), job.updatedAt()))
                .toList();
    }

    /**
     * @param leaseExpiresAt when the host holding this must report back, or it returns to the queue
     * @param attempts       above one means a previous holder never reported
     */
    @Schema(name = "AgentJobResponse", description = "One unit of work for a host that collects it.",
            requiredProperties = {"id", "type", "status", "attempts", "createdAt"})
    public record AgentJobResponse(UUID id, String type, String status, int attempts,
                                   UUID deploymentId, UUID bundleId, Instant leaseExpiresAt,
                                   String error, Instant createdAt, Instant updatedAt) {
    }

    private static NginxInstanceResponse toResponse(NginxInstance instance) {
        return new NginxInstanceResponse(
                instance.id(),
                instance.name(),
                instance.hostname(),
                instance.connectivityMode().name(),
                // Null for a pull host: it is never dialled, so there is no URL and nothing to pin.
                instance.agentBaseUrl() == null ? null : instance.agentBaseUrl().toString(),
                instance.agentCertFingerprint(),
                instance.environment(),
                instance.status().name(),
                instance.nginxVersion(),
                instance.agentVersion(),
                instance.lastSeenAt(),
                instance.createdAt(),
                instance.version());
    }
}
