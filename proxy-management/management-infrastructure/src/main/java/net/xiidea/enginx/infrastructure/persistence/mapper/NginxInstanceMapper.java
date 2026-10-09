package net.xiidea.enginx.infrastructure.persistence.mapper;

import net.xiidea.enginx.domain.certificate.EncryptedSecret;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.infrastructure.persistence.entity.NginxInstanceEntity;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class NginxInstanceMapper {

    public NginxInstance toDomain(NginxInstanceEntity entity) {
        return NginxInstance.rehydrate(
                entity.getId(),
                entity.getName(),
                entity.getHostname(),
                // Null for a pull host, which is never dialled and so has no URL to dial.
                entity.getAgentBaseUrl() == null ? null : URI.create(entity.getAgentBaseUrl()),
                entity.getAgentCertFingerprint(),
                entity.getConnectivityMode(),
                entity.getPushTransport(),
                agentToken(entity),
                entity.getEnvironment(),
                entity.getStatus(),
                entity.getNginxVersion(),
                entity.getAgentVersion(),
                entity.getLastSeenAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    public void applyToEntity(NginxInstance instance, NginxInstanceEntity entity) {
        entity.setName(instance.name());
        entity.setHostname(instance.hostname());
        entity.setAgentBaseUrl(instance.agentBaseUrl() == null ? null : instance.agentBaseUrl().toString());
        entity.setAgentCertFingerprint(instance.agentCertFingerprint());
        entity.setConnectivityMode(instance.connectivityMode());
        entity.setPushTransport(instance.pushTransport());
        EncryptedSecret token = instance.agentToken();
        if (token == null) {
            entity.sealAgentToken(null, null, null, null, null);
        } else {
            entity.sealAgentToken(token.ciphertext(), token.wrappedDataKey(), token.kekId(), token.cipher(), token.iv());
        }
        entity.setEnvironment(instance.environment());
        entity.setStatus(instance.status());
        entity.setNginxVersion(instance.nginxVersion());
        entity.setAgentVersion(instance.agentVersion());
        entity.setLastSeenAt(instance.lastSeenAt());
        entity.setCreatedAt(instance.createdAt());
        entity.setUpdatedAt(instance.updatedAt());
    }

    /** The sealed token, or null. Only the sealed columns: the legacy plaintext one is never read here. */
    public static EncryptedSecret agentToken(NginxInstanceEntity entity) {
        if (entity.getAgentTokenCiphertext() == null) {
            return null;
        }
        return new EncryptedSecret(entity.getAgentTokenCiphertext(), entity.getAgentTokenWrappedDek(),
                entity.getAgentTokenKekId(), entity.getAgentTokenCipher(), entity.getAgentTokenIv(), new byte[0]);
    }
}
