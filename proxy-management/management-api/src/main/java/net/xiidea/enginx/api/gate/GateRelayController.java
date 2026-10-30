package net.xiidea.enginx.api.gate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.xiidea.enginx.application.gate.GateEventService;
import net.xiidea.enginx.application.gate.GateRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * Where a host's gate asks for a sign-in code to be mailed, and reports who came to the door.
 *
 * <p>Under {@code /api/v1/agents}, so it shares the agent filter chain: no bearer token, because
 * the request is authenticated by its signature with the site's relay key instead, which every
 * gated host already holds and nothing else does.
 */
@RestController
@RequestMapping("/api/v1/agents/gate")
@Tag(name = "Gate", description = "Calls from the host-local gate that protects sites with a sign-in")
public class GateRelayController {

    private static final Logger log = LoggerFactory.getLogger(GateRelayController.class);

    private final GateRelayService relay;
    private final GateEventService events;
    private final JsonMapper json;

    public GateRelayController(GateRelayService relay, GateEventService events, JsonMapper json) {
        this.relay = relay;
        this.events = events;
        this.json = json;
    }

    @Schema(name = "GateCodeRequest", requiredProperties = {"siteId", "email", "code", "timestamp", "signature"})
    public record CodeRequest(
            @NotNull UUID siteId,
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 16) String code,
            long timestamp,
            @NotBlank @Size(max = 128) String signature) {

        @Override
        public String toString() {
            return "CodeRequest[siteId=" + siteId + ", email=" + email + ", code=<redacted>]";
        }
    }

    @PostMapping("/otp-mail")
    @Operation(summary = "Mail a sign-in code",
            description = "Signed by the gate with the site's relay key. 204 when sent; 403 when the address is not "
                    + "allowed or the request is not authentic; 429 when rate limited; 503 when mail is not configured.")
    public ResponseEntity<?> sendCode(@Valid @RequestBody CodeRequest request) {
        GateRelayService.Outcome outcome = relay.sendCode(new GateRelayService.CodeRequest(
                request.siteId(), request.email(), request.code(), request.timestamp(), request.signature()));
        return switch (outcome) {
            case SENT -> ResponseEntity.noContent().build();
            case REFUSED, NOT_ALLOWED -> problem(HttpStatus.FORBIDDEN, "Not sent",
                    "The request was not authentic, or the address is not allowed for this site.");
            case RATE_LIMITED -> problem(HttpStatus.TOO_MANY_REQUESTS, "Too many codes",
                    "Too many codes were requested for this address or site. Try again later.");
            case MAIL_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "Mail is not configured",
                    "The platform has no mail server configured, so no code can be sent.");
        };
    }

    /**
     * One site's events, signed by the gate over {@code payload} exactly as sent, so the signature
     * is checked against the bytes that are then parsed rather than against a re-serialisation.
     */
    @Schema(name = "GateEventSection", requiredProperties = {"siteId", "payload", "signature"})
    public record EventSection(
            @NotNull UUID siteId,
            @NotBlank @Size(max = 262_144) String payload,
            @NotBlank @Size(max = 128) String signature) {
    }

    @Schema(name = "GateEventsRequest", requiredProperties = {"sites"})
    public record EventsRequest(@NotNull @Size(max = 200) List<@Valid EventSection> sites) {
    }

    /** A section's payload, as the gate writes it. */
    record EventPayload(UUID siteId, String batchId, long sentAt, int dropped, List<GateEventService.Event> events) {
    }

    @PostMapping("/events")
    @Operation(summary = "Record what a gate saw",
            description = "Sign-ins, failures, refusals and sign-outs at protected sites, for the audit trail. Each "
                    + "section is signed by the gate with its site's relay key, and checked on its own: a section "
                    + "that is not authentic is skipped, and 204 is the answer either way, since sending it again "
                    + "would not make it authentic.")
    public ResponseEntity<Void> recordEvents(@Valid @RequestBody EventsRequest request) {
        for (EventSection section : request.sites()) {
            GateEventService.Outcome outcome;
            try {
                EventPayload payload = json.readValue(section.payload(), EventPayload.class);
                outcome = events.record(section.siteId(), section.payload(), section.signature(),
                        new GateEventService.Batch(payload.siteId(), payload.batchId(), payload.sentAt(),
                                payload.dropped(), payload.events()));
            } catch (JacksonException e) {
                outcome = GateEventService.Outcome.REFUSED;
            }
            if (outcome == GateEventService.Outcome.REFUSED) {
                log.debug("Skipped a gate events section for site {}", section.siteId());
            }
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
