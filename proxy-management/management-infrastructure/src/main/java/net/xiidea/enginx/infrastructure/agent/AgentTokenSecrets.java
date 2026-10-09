package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.application.certificate.SecretRewrapTarget;
import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.certificate.SecretEncryption;
import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.infrastructure.persistence.entity.NginxInstanceEntity;
import net.xiidea.enginx.infrastructure.persistence.mapper.NginxInstanceMapper;
import net.xiidea.enginx.infrastructure.persistence.repository.NginxInstanceJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that opens or reseals an agent token, in one place.
 *
 * <p>Two jobs. Revealing a token at the moment of a call, cached so a deployment does not pay for
 * a key unwrap — a network round trip under Vault — on every request. And moving tokens to a new
 * key when the key-encryption key rotates, without which the old key would stay required by rows
 * nobody thought to look at.
 */
@Component
public class AgentTokenSecrets implements SecretRewrapTarget {

    private final NginxInstanceJpaRepository instances;
    private final SecretEncryption encryption;
    private final Map<UUID, Revealed> revealed = new ConcurrentHashMap<>();

    public AgentTokenSecrets(NginxInstanceJpaRepository instances, SecretEncryption encryption) {
        this.instances = instances;
        this.encryption = encryption;
    }

    /**
     * The plaintext token for this host, for an Authorization header and nothing else.
     *
     * <p>Cached against the ciphertext it came from, so a rotated or re-wrapped token is opened
     * afresh rather than served stale.
     */
    String reveal(NginxInstance instance) {
        EncryptedSecret sealed = instance.agentToken();
        if (sealed == null) {
            throw new AgentException("No agent token is stored for " + instance.name(), false);
        }
        byte[] ciphertext = sealed.ciphertext();
        Revealed cached = revealed.get(instance.id());
        if (cached != null && Arrays.equals(cached.ciphertext(), ciphertext)) {
            return cached.token();
        }
        String token;
        try {
            token = encryption.decrypt(sealed);
        } catch (RuntimeException e) {
            // Usually the key that sealed it is no longer configured. Retrying will not bring it back.
            throw new AgentException("Could not open the agent token for " + instance.name()
                    + ": " + e.getClass().getSimpleName(), e, false);
        }
        revealed.put(instance.id(), new Revealed(ciphertext, token));
        return token;
    }

    @Override
    public String name() {
        return "Agent tokens";
    }

    /**
     * REQUIRES_NEW for the reason given on the ACME account store: the caller scans in a read-only
     * transaction, and joining it would discard every write while reporting them done.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rewrap(String currentKeyId) {
        int moved = 0;
        for (NginxInstanceEntity entity : instances.findByAgentTokenCiphertextIsNotNull()) {
            if (currentKeyId.equals(entity.getAgentTokenKekId())) {
                continue;
            }
            seal(entity, encryption.encrypt(encryption.decrypt(NginxInstanceMapper.agentToken(entity))));
            instances.save(entity);
            moved++;
        }
        return moved;
    }

    private static void seal(NginxInstanceEntity entity, EncryptedSecret sealed) {
        entity.sealAgentToken(sealed.ciphertext(), sealed.wrappedDataKey(), sealed.kekId(),
                sealed.cipher(), sealed.iv());
    }

    private record Revealed(byte[] ciphertext, String token) {

        @Override
        public String toString() {
            return "Revealed[redacted]";
        }
    }
}
