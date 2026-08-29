package net.xiidea.enginx.infrastructure.persistence.mapper;

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
                URI.create(entity.getAgentBaseUrl()),
                entity.getAgentCertFingerprint(),
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
        entity.setAgentBaseUrl(instance.agentBaseUrl().toString());
        entity.setAgentCertFingerprint(instance.agentCertFingerprint());
        entity.setEnvironment(instance.environment());
        entity.setStatus(instance.status());
        entity.setNginxVersion(instance.nginxVersion());
        entity.setAgentVersion(instance.agentVersion());
        entity.setLastSeenAt(instance.lastSeenAt());
        entity.setCreatedAt(instance.createdAt());
        entity.setUpdatedAt(instance.updatedAt());
    }
}
