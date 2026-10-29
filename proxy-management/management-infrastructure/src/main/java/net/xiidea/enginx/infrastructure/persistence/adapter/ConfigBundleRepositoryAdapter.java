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
    private final BundleFileSecrets secrets;

    public ConfigBundleRepositoryAdapter(ConfigBundleJpaRepository repository, BundleFileSecrets secrets) {
        this.repository = repository;
        this.secrets = secrets;
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
                ConfigBundleFileEntity row = new ConfigBundleFileEntity(UUID.randomUUID(), file.path(),
                        file.content(), file.sha256(), file.sensitive(), file.mode());
                if (file.sensitive()) {
                    row.seal(secrets.seal(file.content()));
                }
                entity.attach(row);
            }
        }

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findById(UUID id) {
        return repository.findByIdWithFiles(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findByInstanceAndHash(UUID nginxInstanceId, String contentHash) {
        return repository.findByInstanceAndHashWithFiles(nginxInstanceId, contentHash)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfigBundle> findActiveForInstance(UUID nginxInstanceId) {
        return repository.findByInstanceAndStatusWithFiles(nginxInstanceId, BundleStatus.ACTIVE)
                .map(this::toDomain);
    }

    @Override
    public long nextSequence(UUID nginxInstanceId) {
        return repository.maxSequence(nginxInstanceId) + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfigBundle> findRecentForInstance(UUID nginxInstanceId, int limit) {
        return repository.findByNginxInstanceIdOrderBySequenceDesc(nginxInstanceId, Limit.of(limit)).stream()
                .map(this::toDomain)
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

    private static BundleFile.Access accessOf(String mode) {
        for (BundleFile.Access access : BundleFile.Access.values()) {
            if (access.mode().equals(mode)) {
                return access;
            }
        }
        throw new IllegalStateException("Unknown bundle file mode " + mode);
    }

    /** In the clear, or opened: a row stored before sealing existed may still be either. */
    private String contentOf(ConfigBundleFileEntity file) {
        var sealed = file.sealedContent();
        return sealed == null ? file.getContent() : secrets.reveal(file.getId(), sealed);
    }

    private ConfigBundle toDomain(ConfigBundleEntity entity) {
        List<BundleFile> files = entity.getFiles().stream()
                .map(file -> new BundleFile(file.getRelativePath(), contentOf(file),
                        file.getSha256(), accessOf(file.getFileMode())))
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
