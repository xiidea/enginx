package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.BundleStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.infrastructure.persistence.entity.ConfigBundleEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ConfigBundleFileEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.ConfigBundleJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ConfigBundleRepositoryAdapter implements ConfigBundleRepository {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ConfigBundleJpaRepository repository;

    public ConfigBundleRepositoryAdapter(ConfigBundleJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ConfigBundle save(ConfigBundle bundle) {
        ConfigBundleEntity entity = repository.findById(bundle.id())
                .orElseGet(() -> new ConfigBundleEntity(bundle.id()));

        entity.setNginxInstanceId(bundle.nginxInstanceId());
        entity.setSequence(bundle.sequence());
        entity.setContentHash(bundle.contentHash());
        entity.setRenderStatus(bundle.status());
        entity.setSiteIdsSnapshot(JSON.writeValueAsString(
                bundle.siteIds().stream().map(UUID::toString).toList()));
        entity.setCreatedBy(bundle.createdBy());
        entity.setCreatedAt(bundle.createdAt());

        if (entity.getFiles().isEmpty()) {
            for (BundleFile file : bundle.files()) {
                entity.attach(new ConfigBundleFileEntity(UUID.randomUUID(), file.path(),
                        file.content(), file.sha256(), file.sensitive()));
            }
        }

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findById(UUID id) {
        return repository.findByIdWithFiles(id).map(ConfigBundleRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findByInstanceAndHash(UUID nginxInstanceId, String contentHash) {
        return repository.findByInstanceAndHashWithFiles(nginxInstanceId, contentHash)
                .map(ConfigBundleRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findActiveForInstance(UUID nginxInstanceId) {
        return repository.findByInstanceAndStatusWithFiles(nginxInstanceId, BundleStatus.ACTIVE)
                .map(ConfigBundleRepositoryAdapter::toDomain);
    }

    @Override
    public long nextSequence(UUID nginxInstanceId) {
        return repository.maxSequence(nginxInstanceId) + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigBundle> findRecentForInstance(UUID nginxInstanceId, int limit) {
        return repository.findByNginxInstanceIdOrderBySequenceDesc(nginxInstanceId, Limit.of(limit)).stream()
                .map(ConfigBundleRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void markActive(UUID nginxInstanceId, UUID bundleId) {
        // Demote first: a partial unique index allows only one ACTIVE row per instance, so
        // promoting before demoting would collide within the transaction.
        repository.supersedeOthers(nginxInstanceId, bundleId);
        repository.updateStatus(bundleId, BundleStatus.ACTIVE);
    }

    @Override
    @Transactional
    public void updateStatus(UUID bundleId, BundleStatus status) {
        repository.updateStatus(bundleId, status);
    }

    private static ConfigBundle toDomain(ConfigBundleEntity entity) {
        List<BundleFile> files = entity.getFiles().stream()
                .map(file -> new BundleFile(file.getRelativePath(), file.getContent(),
                        file.getSha256(), file.isSensitive()))
                .toList();

        Set<UUID> siteIds = new LinkedHashSet<>();
        for (Object id : JSON.readValue(entity.getSiteIdsSnapshot(), List.class)) {
            siteIds.add(UUID.fromString(String.valueOf(id)));
        }

        return new ConfigBundle(entity.getId(), entity.getNginxInstanceId(), entity.getSequence(),
                entity.getContentHash(), files, siteIds, entity.getRenderStatus(),
                entity.getCreatedBy(), entity.getCreatedAt());
    }
}
