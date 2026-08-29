package net.xiidea.enginx.infrastructure.persistence.repository;

import net.xiidea.enginx.infrastructure.persistence.entity.SiteNotificationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SiteNotificationSettingsJpaRepository
        extends JpaRepository<SiteNotificationSettingsEntity, UUID> {

    List<SiteNotificationSettingsEntity> findByProxySiteIdIn(Collection<UUID> proxySiteIds);
}
