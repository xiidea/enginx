package net.xiidea.enginx.infrastructure.persistence.adapter;

import net.xiidea.enginx.domain.notification.SiteNotificationSettings;
import net.xiidea.enginx.domain.notification.SiteNotificationSettingsRepository;
import net.xiidea.enginx.infrastructure.persistence.entity.SiteNotificationSettingsEntity;
import net.xiidea.enginx.infrastructure.persistence.repository.SiteNotificationSettingsJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SiteNotificationSettingsRepositoryAdapter implements SiteNotificationSettingsRepository {

    private final SiteNotificationSettingsJpaRepository repository;

    public SiteNotificationSettingsRepositoryAdapter(SiteNotificationSettingsJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public SiteNotificationSettings save(SiteNotificationSettings settings) {
        SiteNotificationSettingsEntity entity = repository.findById(settings.proxySiteId())
                .orElseGet(() -> new SiteNotificationSettingsEntity(settings.proxySiteId()));
        entity.setExpiryEnabled(settings.expiryEnabled());
        entity.setSubscribers(settings.subscribers());
        entity.setUpdatedBy(settings.updatedBy());
        entity.setUpdatedAt(settings.updatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<SiteNotificationSettings> findBySiteId(UUID proxySiteId) {
        return repository.findById(proxySiteId)
                .map(SiteNotificationSettingsRepositoryAdapter::toDomain);
    }

    @Override
    public void deleteBySiteId(UUID proxySiteId) {
        repository.deleteById(proxySiteId);
    }

    @Override
    public Map<UUID, SiteNotificationSettings> findForSites(Set<UUID> proxySiteIds) {
        if (proxySiteIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByProxySiteIdIn(proxySiteIds).stream()
                .map(SiteNotificationSettingsRepositoryAdapter::toDomain)
                .collect(Collectors.toMap(SiteNotificationSettings::proxySiteId, Function.identity()));
    }

    private static SiteNotificationSettings toDomain(SiteNotificationSettingsEntity entity) {
        return SiteNotificationSettings.rehydrate(entity.getProxySiteId(), entity.isExpiryEnabled(),
                entity.getSubscribers(), entity.getUpdatedBy(), entity.getUpdatedAt());
    }
}
