package net.xiidea.enginx.application.agent;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.agent.AgentRegistrationToken;
import net.xiidea.enginx.domain.agent.AgentRegistrationTokenRepository;
import net.xiidea.enginx.domain.agent.AgentSecret;
import net.xiidea.enginx.domain.agent.AgentToken;
import net.xiidea.enginx.domain.agent.AgentTokenRepository;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enrolling a host that dials the platform instead of being dialled.
 *
 * <p>The exchange is deliberately one-way: an operator mints a registration token and puts it on
 * the host; the host presents it once and receives a long-lived token of its own. Nothing about
 * the host needs to be known in advance, and nothing needs to be routable to it — which is the
 * entire point, since a host behind NAT can satisfy neither condition.
 */
@Service
public class AgentEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentEnrolmentService.class);
    private static final String TOKEN_RESOURCE = "AGENT_REGISTRATION_TOKEN";
    private static final String INSTANCE_RESOURCE = "NGINX_INSTANCE";

    private final AgentRegistrationTokenRepository tokens;
    private final AgentTokenRepository agentTokens;
    private final NginxInstanceRepository instances;
    private final SitePermissionService permissions;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final EnrolmentRefusalRecorder refusals;
    private final Clock clock;

    public AgentEnrolmentService(AgentRegistrationTokenRepository tokens, AgentTokenRepository agentTokens,
                                 NginxInstanceRepository instances, SitePermissionService permissions,
                                 ActorProvider actors, AuditRecorder audit,
                                 EnrolmentRefusalRecorder refusals, Clock clock) {
        this.tokens = tokens;
        this.agentTokens = agentTokens;
        this.instances = instances;
        this.permissions = permissions;
        this.actors = actors;
        this.audit = audit;
        this.refusals = refusals;
        this.clock = clock;
    }

    /**
     * Mints a registration token.
     *
     * <p>Global admin, and not merely because it creates something: this token can bring a host
     * into the estate, and a host in the estate receives configuration bundles containing every
     * site's private key.
     *
     * @return the token in clear, which is the only time it exists anywhere but as a digest
     */
    @Transactional
    public Minted mintRegistrationToken(String description, Instant expiresAt, Integer maxUses) {
        permissions.requireGlobalAdmin();

        String secret = AgentSecret.mintRegistrationToken();
        AgentRegistrationToken token = AgentRegistrationToken.issue(UUID.randomUUID(),
                AgentSecret.hash(secret), description, expiresAt, maxUses,
                actors.currentActor().username(), clock.instant());

        AgentRegistrationToken saved = tokens.save(token);
        audit.success(AuditAction.AGENT_REGISTRATION_TOKEN_CREATED, TOKEN_RESOURCE, saved.id(), null,
                snapshot(saved));
        return new Minted(saved, secret);
    }

    @Transactional(readOnly = true)
    public List<AgentRegistrationToken> listRegistrationTokens() {
        permissions.requireGlobalAdmin();
        return tokens.findAll();
    }

    @Transactional
    public void revokeRegistrationToken(UUID id) {
        permissions.requireGlobalAdmin();

        AgentRegistrationToken token = tokens.findById(id)
                .orElseThrow(() -> new NotFoundException(TOKEN_RESOURCE, id));
        Map<String, Object> before = snapshot(token);
        token.revoke(clock.instant());

        tokens.save(token);
        audit.success(AuditAction.AGENT_REGISTRATION_TOKEN_REVOKED, TOKEN_RESOURCE, id, before,
                snapshot(token));
    }

    /**
     * Enrols a host presenting a registration token.
     *
     * <p>Unauthenticated in the ordinary sense — the caller is a machine nobody has met, and the
     * token is the whole of its claim. Every refusal is audited with the reason, because a burst
     * of them is what an attacker guessing tokens looks like.
     *
     * @return the instance and its new agent token, in clear, once
     */
    @Transactional
    public Enrolled register(String registrationToken, String name, String hostname, String environment) {
        Instant now = clock.instant();
        AgentRegistrationToken token = tokens.findByTokenHash(AgentSecret.hash(registrationToken))
                .orElse(null);

        if (token == null || !token.isUsableAt(now)) {
            // One message for every cause. Telling a caller that a token exists but has expired
            // confirms the token, which is the one bit of information worth having.
            refusals.record(name, hostname, token == null ? "unknown" : token.unusableReason(now));
            throw new ValidationException("registrationToken", "That registration token cannot be used");
        }

        if (instances.existsByName(name)) {
            throw new ConflictException("An NGINX instance named '" + name + "' is already registered");
        }

        NginxInstance instance = instances.save(
                NginxInstance.registerPull(UUID.randomUUID(), name, hostname, environment, now));

        String secret = AgentSecret.mintAgentToken();
        agentTokens.save(AgentToken.issue(UUID.randomUUID(), instance.id(), AgentSecret.hash(secret), now));

        token.recordUse(now);
        tokens.save(token);

        audit.success(AuditAction.AGENT_REGISTERED, INSTANCE_RESOURCE, instance.id(), null,
                Map.of("name", instance.name(), "hostname", instance.hostname(),
                        "connectivityMode", instance.connectivityMode().name(),
                        "registrationTokenId", token.id().toString()));
        log.info("Host '{}' enrolled itself as a pull-mode instance", instance.name());

        return new Enrolled(instance, secret);
    }

    /**
     * Revokes a host's token, which stops it collecting work until it re-enrols.
     *
     * <p>Does not delete the instance: its deployment history and the sites that point at it
     * outlive one credential.
     */
    @Transactional
    public void revokeAgentToken(UUID instanceId) {
        permissions.requireGlobalAdmin();

        NginxInstance instance = instances.findById(instanceId)
                .orElseThrow(() -> new NotFoundException(INSTANCE_RESOURCE, instanceId));

        Optional<AgentToken> active = agentTokens.findActiveByInstanceId(instanceId);
        if (active.isEmpty()) {
            throw new ConflictException("That instance has no active agent token");
        }

        AgentToken token = active.get();
        token.revoke(clock.instant());
        agentTokens.save(token);

        audit.success(AuditAction.AGENT_TOKEN_REVOKED, INSTANCE_RESOURCE, instanceId, null,
                Map.of("name", instance.name()));
    }

    private static Map<String, Object> snapshot(AgentRegistrationToken token) {
        return Map.of(
                "description", String.valueOf(token.description()),
                "expiresAt", String.valueOf(token.expiresAt()),
                "maxUses", String.valueOf(token.maxUses()),
                "uses", String.valueOf(token.uses()),
                "revoked", String.valueOf(token.revokedAt() != null));
    }

    /** @param secret the token in clear. Returned once and never retrievable again. */
    public record Minted(AgentRegistrationToken token, String secret) {
    }

    /** @param secret the agent token in clear. Returned once and never retrievable again. */
    public record Enrolled(NginxInstance instance, String secret) {
    }
}
