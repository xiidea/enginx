package net.xiidea.enginx.api.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import net.xiidea.enginx.application.agent.AgentAuthenticationService;
import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.application.agent.AgentHeartbeatService;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What a pull-mode agent calls.
 *
 * <p>Every host here dials the platform rather than being dialled, so nothing needs a route to it.
 * That is the whole feature: a host behind NAT, in another cloud, or on a network the management
 * plane cannot reach is managed exactly like one sitting beside it.
 *
 * <p>Callers are machines, not people. They authenticate with a token rather than a JWT, which is
 * why these paths have a security chain of their own — see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Agents", description = "Enrolment and reporting for hosts that call the platform")
public class AgentController {

    private final AgentEnrolmentService enrolment;
    private final AgentAuthenticationService authentication;
    private final AgentHeartbeatService heartbeat;

    public AgentController(AgentEnrolmentService enrolment, AgentAuthenticationService authentication,
                           AgentHeartbeatService heartbeat) {
        this.enrolment = enrolment;
        this.authentication = authentication;
        this.heartbeat = heartbeat;
    }

    @PostMapping("/register")
    @Operation(summary = "Enrol this host",
            description = "Presents a registration token and receives a long-lived agent token. "
                    + "The agent token is returned once and is never retrievable again — the "
                    + "platform stores only its digest.")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        AgentEnrolmentService.Enrolled enrolled = enrolment.register(
                request.registrationToken(), request.name(), request.hostname(), request.environment());

        return new RegisterResponse(enrolled.instance().id(), enrolled.instance().name(),
                enrolled.secret());
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "Report this host's state",
            description = "Replaces the status call the platform makes to a push-mode host. The "
                    + "reported values are interpreted identically, so ONLINE and DEGRADED mean "
                    + "the same thing whichever end of the connection asked.")
    public ResponseEntity<Void> heartbeat(
            // Optional deliberately. Declared required, a missing header fails binding before the
            // check below runs, so "no credential" would answer differently from "wrong
            // credential" -- and the difference is exactly what a caller guessing wants to learn.
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody HeartbeatRequest request) {
        NginxInstance instance = requireAgent(authorization);

        heartbeat.accept(instance, new AgentStatus(
                request.agentVersion(), request.nginxVersion(),
                request.isNginxRunning(), request.activeBundleId(),
                request.isConfigTestOk(), request.configTestOutput(), List.of()));

        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves the bearer token, or refuses.
     *
     * <p>One message for every failure — unknown, revoked, malformed. Distinguishing them tells a
     * caller which of its guesses was closer, which is the only useful thing to learn when
     * guessing.
     */
    private NginxInstance requireAgent(String authorization) {
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : null;

        return authentication.authenticate(token)
                .orElseThrow(() -> new AccessDeniedException("That agent token is not recognised"));
    }

    @Schema(name = "AgentRegisterRequest", requiredProperties = {"registrationToken", "name", "hostname"})
    public record RegisterRequest(
            @NotBlank(message = "A registration token is required") @Size(max = 128) String registrationToken,
            @NotBlank(message = "A name is required") @Size(max = 64) String name,
            @NotBlank(message = "A hostname is required") @Size(max = 253) String hostname,
            @Size(max = 32) String environment) {
    }

    /** @param agentToken shown once. The platform keeps only a digest and cannot return it again. */
    @Schema(name = "AgentRegisterResponse", requiredProperties = {"instanceId", "name", "agentToken"})
    public record RegisterResponse(UUID instanceId, String name, String agentToken) {
    }

    @Schema(name = "AgentHeartbeatRequest", requiredProperties = {"nginxRunning", "configTestOk"})
    public record HeartbeatRequest(
            @Size(max = 32) String agentVersion,
            @Size(max = 32) String nginxVersion,
            Boolean nginxRunning,
            @Size(max = 128) String activeBundleId,
            Boolean configTestOk,
            @Size(max = 4096) String configTestOutput) {

        // Boxed components, then defaulted here: a primitive component makes Jackson reject a
        // body that omits it, turning an optional field into a required one the schema does not
        // declare. A record's accessor cannot narrow the component's type, hence the names.
        public boolean isNginxRunning() {
            return Boolean.TRUE.equals(nginxRunning);
        }

        public boolean isConfigTestOk() {
            return Boolean.TRUE.equals(configTestOk);
        }
    }
}
