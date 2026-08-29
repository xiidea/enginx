package net.xiidea.enginx.api.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import net.xiidea.enginx.application.agent.AgentEnrolmentService;
import net.xiidea.enginx.domain.agent.AgentRegistrationToken;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Minting the credential a new host uses to enrol itself.
 *
 * <p>Global admin throughout, and not merely because it creates something: this token brings a
 * host into the estate, and a host in the estate receives configuration bundles containing every
 * site's private key.
 */
@RestController
@RequestMapping("/api/v1/agent-registration-tokens")
@Tag(name = "Agent registration", description = "Credentials that let a host enrol itself")
public class AgentRegistrationTokenController {

    private final AgentEnrolmentService enrolment;

    public AgentRegistrationTokenController(AgentEnrolmentService enrolment) {
        this.enrolment = enrolment;
    }

    @GetMapping
    @Operation(summary = "List registration tokens",
            description = "Never returns a token, only what is known about it. The secret exists "
                    + "in clear exactly once, in the response that created it.")
    public List<Response> list() {
        return enrolment.listRegistrationTokens().stream().map(AgentRegistrationTokenController::toResponse).toList();
    }

    @PostMapping
    @Operation(summary = "Mint a registration token",
            description = "The response carries the token in clear. It is not stored and cannot be "
                    + "shown again; a lost token is replaced rather than recovered.")
    public ResponseEntity<CreatedResponse> create(@Valid @RequestBody CreateRequest request) {
        AgentEnrolmentService.Minted minted = enrolment.mintRegistrationToken(
                request.description(), request.expiresAt(), request.maxUses());

        return ResponseEntity.status(201)
                .body(new CreatedResponse(toResponse(minted.token()), minted.secret()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a registration token",
            description = "Stops it enrolling anything further. Hosts already enrolled with it "
                    + "keep working: they hold agent tokens of their own, issued separately.")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        enrolment.revokeRegistrationToken(id);
        return ResponseEntity.noContent().build();
    }

    private static Response toResponse(AgentRegistrationToken token) {
        return new Response(token.id(), token.description(), token.expiresAt(), token.maxUses(),
                token.uses(), token.revokedAt(), token.createdBy(), token.createdAt(),
                token.isUsableAt(Instant.now()));
    }

    /**
     * @param maxUses null for unlimited, which is a choice an operator has to make deliberately
     * @param usable  whether it would be accepted right now, so the console need not re-derive it
     */
    @Schema(name = "AgentRegistrationTokenResponse",
            requiredProperties = {"id", "uses", "createdBy", "createdAt", "usable"})
    public record Response(UUID id, String description, Instant expiresAt, Integer maxUses, int uses,
                           Instant revokedAt, String createdBy, Instant createdAt, boolean usable) {
    }

    /** @param token the secret, in clear, for the only time it is ever available */
    @Schema(name = "AgentRegistrationTokenCreatedResponse", requiredProperties = {"registrationToken", "token"})
    public record CreatedResponse(Response registrationToken, String token) {
    }

    @Schema(name = "CreateAgentRegistrationTokenRequest")
    public record CreateRequest(@Size(max = 256) String description, Instant expiresAt, Integer maxUses) {
    }
}
