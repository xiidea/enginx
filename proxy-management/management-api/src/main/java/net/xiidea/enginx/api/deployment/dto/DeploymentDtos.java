package net.xiidea.enginx.api.deployment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeploymentDtos {

    private DeploymentDtos() {
    }

    @Schema(name = "DeploymentResponse",
            requiredProperties = {"id", "nginxInstanceId", "trigger", "status", "attempt", "createdBy", "createdAt", "events"}, description = "One deployment and its phase history.")
    public record Response(
            UUID id,
            UUID nginxInstanceId,
            UUID configBundleId,
            UUID previousBundleId,
            String trigger,
            String status,
            int attempt,
            String nginxTestOutput,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            String createdBy,
            Instant createdAt,
            List<EventResponse> events) {
    }

    @Schema(name = "DeploymentEventResponse", description = "One deployment phase.", requiredProperties = {"phase", "result", "at"})
    public record EventResponse(String phase, String result, String detail, Instant at) {
    }

    /**
     * @param files public files only. Private key material is never returned, at any permission
     *              level, so the bundle view can be shown without a second thought.
     */
    public record BundleResponse(
            UUID id,
            UUID nginxInstanceId,
            long sequence,
            String contentHash,
            String status,
            int siteCount,
            List<BundleFileResponse> files,
            String createdBy,
            Instant createdAt) {
    }

    public record BundleFileResponse(String path, String sha256, int sizeBytes, String content) {
    }

    public record BundleSummaryResponse(
            UUID id,
            long sequence,
            String contentHash,
            String status,
            int siteCount,
            Instant createdAt) {
    }

    @Schema(name = "ConfigurationPreviewResponse",
            requiredProperties = {"siteConfiguration", "proposedContentHash", "changed", "changedPaths"}, description = "The configuration a site would produce, and what would change on the host.")
    public record PreviewResponse(
            String siteConfiguration,
            String currentContentHash,
            String proposedContentHash,
            boolean changed,
            List<String> changedPaths) {
    }

    /**
     * Whether one upstream accepted a connection from the host that will proxy to it.
     *
     * @param target the probed host and port, as configured
     * @param error  why the connection failed, or null when it succeeded
     */
    @Schema(name = "UpstreamCheckResponse", requiredProperties = {"target", "reachable"})
    public record UpstreamCheckResponse(String target, boolean reachable, String error) {
    }
}
