package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.nginx.PushTransport;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registration and lookup of managed NGINX hosts.
 *
 * <p>Phase 2 covers registration and listing only. Reaching out to an agent over mTLS to observe
 * its real status arrives with the agent client in Phase 4.
 */
@Service
public class NginxInstanceService {

    private static final String RESOURCE_TYPE = "NGINX_INSTANCE";

    private final NginxInstanceRepository instances;
    private final AuditRecorder audit;
    private final SecretEncryption encryption;
    private final Clock clock;

    public NginxInstanceService(NginxInstanceRepository instances, AuditRecorder audit,
                                SecretEncryption encryption, Clock clock) {
        this.instances = instances;
        this.audit = audit;
        this.encryption = encryption;
        this.clock = clock;
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPERATOR','READ_ONLY')")
    @Transactional(readOnly = true)
    public List<NginxInstance> findAll() {
        return instances.findAll();
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','OPERATOR','READ_ONLY')")
    @Transactional(readOnly = true)
    public NginxInstance get(UUID id) {
        return instances.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }

    /**
     * Trusts a new agent certificate for this host.
     *
     * <p>Rotating an agent's certificate previously meant deleting and re-registering the
     * instance, which discards its deployment history and its link from every site on it. This
     * changes the one field that actually changed.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public NginxInstance rotateAgentCertificate(UUID id, String newFingerprint) {
        NginxInstance instance = instances.findById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));

        // singletonMap, not Map.of: Map.of refuses a null value, and the domain call below is what
        // reports a host with no fingerprint properly. Failing here first would answer with a 500.
        Map<String, Object> before = Collections.singletonMap("agentCertFingerprint", instance.agentCertFingerprint());
        instance.agentCertificateRotated(newFingerprint, clock.instant());
        NginxInstance saved = instances.save(instance);

        // Audited with both fingerprints. Which certificate a host is trusted under, and when that
        // changed, is precisely the question an incident review asks.
        audit.success(AuditAction.NGINX_INSTANCE_CERT_ROTATED, RESOURCE_TYPE, id, before,
                Map.of("agentCertFingerprint", saved.agentCertFingerprint()));
        return saved;
    }

    /**
     * Trusts a new token for a host the platform dials with one.
     *
     * <p>The counterpart of {@link #rotateAgentCertificate}, and for the same reason: without it a
     * leaked token could only be revoked by deleting the instance, and its history with it.
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public NginxInstance rotateAgentToken(UUID id, String newToken) {
        NginxInstance instance = instances.findById(id)
                .orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));

        instance.agentTokenRotated(encryption.encrypt(NginxInstance.validAgentToken(newToken)), clock.instant());
        NginxInstance saved = instances.save(instance);

        // That it changed and when, never what to. Even a digest of a token is a way to confirm a
        // guess at it.
        audit.success(AuditAction.NGINX_INSTANCE_TOKEN_ROTATED, RESOURCE_TYPE, id, null,
                Map.of("pushTransport", saved.pushTransport().name()));
        return saved;
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public NginxInstance register(String name, String hostname, String agentBaseUrl,
                                  String agentCertFingerprint, String environment) {
        return registerPush(name, hostname, agentBaseUrl, PushTransport.MTLS, agentCertFingerprint, null, environment);
    }

    /**
     * Registers a host the platform dials.
     *
     * @param agentToken plaintext, for {@link PushTransport#HTTP_TOKEN}. Sealed before it reaches
     *                   the aggregate, and never stored or audited in clear
     */
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public NginxInstance registerPush(String name, String hostname, String agentBaseUrl,
                                      PushTransport pushTransport, String agentCertFingerprint,
                                      String agentToken, String environment) {
        boolean tokenTransport = pushTransport != null && pushTransport.isToken();
        if (!tokenTransport && agentToken != null && !agentToken.isBlank()) {
            // Refused rather than dropped: an operator who sent a token believes the host uses it.
            throw new ValidationException("agentAuthToken",
                    "A host that authenticates with a certificate takes no token. Set pushTransport to HTTP_TOKEN");
        }
        EncryptedSecret sealed = tokenTransport
                ? encryption.encrypt(NginxInstance.validAgentToken(agentToken))
                : null;
        NginxInstance instance = NginxInstance.registerPush(
                UUID.randomUUID(), name, hostname, agentBaseUrl, pushTransport,
                agentCertFingerprint, sealed, environment, clock.instant());

        if (instances.existsByName(instance.name())) {
            throw new ConflictException("An NGINX instance named '" + instance.name() + "' already exists");
        }

        NginxInstance saved = instances.save(instance);
        audit.success(AuditAction.NGINX_INSTANCE_REGISTERED, RESOURCE_TYPE, saved.id(), null,
                Map.of("name", saved.name(),
                        "hostname", saved.hostname(),
                        "agentBaseUrl", saved.agentBaseUrl().toString(),
                        "pushTransport", saved.pushTransport().name(),
                        "environment", saved.environment()));
        return saved;
    }
}
