package net.xiidea.enginx.api.deployment;

import net.xiidea.enginx.api.common.PageResponse;
import net.xiidea.enginx.api.deployment.dto.DeploymentDtos;
import net.xiidea.enginx.application.deployment.ConfigurationPreview;
import net.xiidea.enginx.application.deployment.ConfigurationPreviewService;
import net.xiidea.enginx.application.deployment.DeploymentService;
import net.xiidea.enginx.application.nginx.UpstreamCheckService;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.Deployment;
import net.xiidea.enginx.domain.deployment.DeploymentStatus;
import net.xiidea.enginx.domain.shared.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deployments", description = "Rendering, validating and activating NGINX configuration")
public class DeploymentController {

    private final DeploymentService deployments;
    private final ConfigurationPreviewService previews;
    private final UpstreamCheckService upstreamChecks;

    public DeploymentController(DeploymentService deployments, ConfigurationPreviewService previews,
                                UpstreamCheckService upstreamChecks) {
        this.deployments = deployments;
        this.previews = previews;
        this.upstreamChecks = upstreamChecks;
    }

    @PostMapping("/proxy-sites/{id}/deploy")
    @Operation(summary = "Deploy the NGINX instance this site belongs to",
            description = "Returns 202: the bundle is rendered from current state and applied by the "
                    + "dispatcher, so the response arrives before the host has been changed. Poll the "
                    + "deployment for the outcome.")
    public ResponseEntity<DeploymentDtos.Response> deploySite(@PathVariable UUID id,
                                                              UriComponentsBuilder uriBuilder) {
        Deployment deployment = deployments.deploySite(id);
        return accepted(deployment, uriBuilder);
    }

    @PostMapping("/nginx-instances/{id}/deploy")
    @Operation(summary = "Deploy an entire NGINX instance")
    public ResponseEntity<DeploymentDtos.Response> deployInstance(@PathVariable UUID id,
                                                                   UriComponentsBuilder uriBuilder) {
        return accepted(deployments.deployInstance(id), uriBuilder);
    }

    @GetMapping("/proxy-sites/{id}/preview")
    @Operation(summary = "Show the configuration this site would produce",
            description = "Renders without deploying, and reports which files would change on the host.")
    public DeploymentDtos.PreviewResponse preview(@PathVariable UUID id) {
        ConfigurationPreview preview = previews.previewSite(id);
        return new DeploymentDtos.PreviewResponse(
                preview.siteConfiguration(),
                preview.currentContentHash(),
                preview.proposedContentHash(),
                preview.changed(),
                preview.changedPaths());
    }

    @GetMapping("/proxy-sites/{id}/upstream-check")
    @Operation(summary = "Ask the host whether this site's upstreams accept a connection",
            description = "Advisory. NGINX Open Source resolves upstream names once when the "
                    + "configuration loads, so a reachable upstream now may still fail later; this "
                    + "catches a typo or a service that is not running. The probe runs on the NGINX "
                    + "host, which is where the answer means something and where NGINX will connect "
                    + "from — the management server never dials a user-supplied address.")
    public List<DeploymentDtos.UpstreamCheckResponse> checkUpstreams(@PathVariable UUID id) {
        return upstreamChecks.check(id).stream()
                .map(result -> new DeploymentDtos.UpstreamCheckResponse(
                        result.target(), result.reachable(), result.error()))
                .toList();
    }

    @GetMapping("/deployments")
    @Operation(summary = "List deployments")
    public PageResponse<DeploymentDtos.Response> list(
            @RequestParam(required = false) UUID nginxInstanceId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.from(
                deployments.search(nginxInstanceId, parseStatuses(status), page, size),
                DeploymentController::toResponse);
    }

    @GetMapping("/deployments/{id}")
    @Operation(summary = "Fetch one deployment with its phase history")
    public DeploymentDtos.Response get(@PathVariable UUID id) {
        return toResponse(deployments.get(id));
    }

    @PostMapping("/deployments/{id}/rollback")
    @Operation(summary = "Re-activate the configuration this deployment replaced",
            description = "Queues a new deployment of the previous bundle. Rollback is never automatic: "
                    + "the only automatic revert is the agent restoring the previous release when a "
                    + "reload fails, which is a refusal to leave the host broken.")
    public ResponseEntity<DeploymentDtos.Response> rollback(@PathVariable UUID id,
                                                             UriComponentsBuilder uriBuilder) {
        Deployment source = deployments.get(id);
        if (source.previousBundleId() == null) {
            throw new ValidationException("previousBundleId",
                    "This deployment did not replace an earlier configuration, so there is nothing to roll back to");
        }
        return accepted(deployments.rollback(source.previousBundleId()), uriBuilder);
    }

    @GetMapping("/nginx-instances/{id}/bundles")
    @Operation(summary = "Recent configuration bundles for an instance, newest first")
    public List<DeploymentDtos.BundleSummaryResponse> bundles(@PathVariable UUID id,
                                                               @RequestParam(defaultValue = "20") int limit) {
        return deployments.recentBundles(id, Math.clamp(limit, 1, 100)).stream()
                .map(bundle -> new DeploymentDtos.BundleSummaryResponse(
                        bundle.id(), bundle.sequence(), bundle.contentHash(),
                        bundle.status().name(), bundle.siteIds().size(), bundle.createdAt()))
                .toList();
    }

    @GetMapping("/config-bundles/{id}")
    @Operation(summary = "Inspect a configuration bundle",
            description = "Private key material is excluded from the file list at every permission level.")
    public DeploymentDtos.BundleResponse bundle(@PathVariable UUID id) {
        ConfigBundle bundle = deployments.bundle(id);

        List<DeploymentDtos.BundleFileResponse> files = new ArrayList<>();
        for (BundleFile file : bundle.publicFiles()) {
            files.add(new DeploymentDtos.BundleFileResponse(file.path(), file.sha256(),
                    file.content().getBytes(StandardCharsets.UTF_8).length, file.content()));
        }

        return new DeploymentDtos.BundleResponse(bundle.id(), bundle.nginxInstanceId(), bundle.sequence(),
                bundle.contentHash(), bundle.status().name(), bundle.siteIds().size(), files,
                bundle.createdBy(), bundle.createdAt());
    }

    private ResponseEntity<DeploymentDtos.Response> accepted(Deployment deployment,
                                                              UriComponentsBuilder uriBuilder) {
        URI location = uriBuilder.path("/api/v1/deployments/{id}").buildAndExpand(deployment.id()).toUri();
        return ResponseEntity.accepted().location(location).body(toResponse(deployment));
    }

    private static List<DeploymentStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<DeploymentStatus> statuses = new ArrayList<>();
        for (String value : raw) {
            try {
                statuses.add(DeploymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("status", "'" + value + "' is not a deployment status");
            }
        }
        return statuses;
    }

    private static DeploymentDtos.Response toResponse(Deployment deployment) {
        List<DeploymentDtos.EventResponse> events = deployment.events().stream()
                .map(event -> new DeploymentDtos.EventResponse(event.phase().name(),
                        event.result().name(), event.detail(), event.at()))
                .toList();

        return new DeploymentDtos.Response(
                deployment.id(),
                deployment.nginxInstanceId(),
                deployment.configBundleId(),
                deployment.previousBundleId(),
                deployment.trigger().name(),
                deployment.status().name(),
                deployment.attempt(),
                deployment.nginxTestOutput(),
                deployment.errorMessage(),
                deployment.startedAt(),
                deployment.finishedAt(),
                deployment.createdBy(),
                deployment.createdAt(),
                events);
    }
}
