package net.xiidea.enginx.domain.notification;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SiteNotificationSettingsRepository {

    SiteNotificationSettings save(SiteNotificationSettings settings);

    Optional<SiteNotificationSettings> findBySiteId(UUID proxySiteId);

    void deleteBySiteId(UUID proxySiteId);

    /**
     * Settings for several sites at once, for the notification sweep.
     *
     * <p>One query for the batch rather than one per site: the scan already reads every site
     * approaching expiry, and asking again per site would turn one indexed read into a hundred.
     * Sites with no row are absent from the result and take the defaults.
     */
    Map<UUID, SiteNotificationSettings> findForSites(Set<UUID> proxySiteIds);
}
