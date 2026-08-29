package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.notification.SiteNotificationSettings;
import net.xiidea.enginx.domain.notification.SiteNotificationSettingsRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who is told about a site, and whether.
 *
 * <p>Deliberately not part of updating the site. Changing an address is not changing what the site
 * serves: it takes no optimistic lock on the configuration, does not appear as a configuration
 * change in the audit trail, and needs no authority over routing.
 */
@Service
public class SiteNotificationService {

    private static final String RESOURCE_TYPE = "PROXY_SITE";

    private final SiteNotificationSettingsRepository settings;
    private final ProxySiteRepository sites;
    private final SitePermissionService permissions;
    private final ActorProvider actors;
    private final AuditRecorder audit;
    private final Clock clock;

    public SiteNotificationService(SiteNotificationSettingsRepository settings, ProxySiteRepository sites,
                                   SitePermissionService permissions, ActorProvider actors,
                                   AuditRecorder audit, Clock clock) {
        this.settings = settings;
        this.sites = sites;
        this.permissions = permissions;
        this.actors = actors;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Whoever may see the site may see who is told about it.
     *
     * <p>A site nobody has configured has no row, and gets the defaults — absence and "the
     * defaults" are the same state, so a site creation does not carry a second insert to say
     * nothing.
     */
    @Transactional(readOnly = true)
    public SiteNotificationSettings get(UUID siteId) {
        ProxySite site = load(siteId);
        permissions.requireSiteAccess(site, PermissionLevel.READ);

        return settings.findBySiteId(siteId).orElseGet(() -> SiteNotificationSettings.defaultsFor(siteId));
    }

    /**
     * Replaces the settings.
     *
     * <p>OPERATE rather than MANAGE: this is operational, like enabling or renewing, and does not
     * change what anybody is served. It is not READ either, because an address here receives mail
     * from the platform naming a domain and its expiry.
     */
    @Transactional
    public SiteNotificationSettings configure(UUID siteId, boolean expiryEnabled, Set<String> subscribers) {
        ProxySite site = load(siteId);
        permissions.requireSiteAccess(site, PermissionLevel.OPERATE);

        SiteNotificationSettings current = settings.findBySiteId(siteId)
                .orElseGet(() -> SiteNotificationSettings.defaultsFor(siteId));
        Map<String, Object> before = snapshot(current);

        current.configure(expiryEnabled, subscribers, actors.currentActor().username(), clock.instant());

        // Back to the defaults means back to no row. Keeping one that says nothing would leave the
        // table growing with rows that carry no information.
        SiteNotificationSettings saved;
        if (current.isDefault()) {
            settings.deleteBySiteId(siteId);
            saved = SiteNotificationSettings.defaultsFor(siteId);
        } else {
            saved = settings.save(current);
        }

        audit.success(AuditAction.PROXY_SITE_NOTIFICATIONS_UPDATED, RESOURCE_TYPE, siteId,
                before, snapshot(saved));
        return saved;
    }

    private ProxySite load(UUID siteId) {
        return sites.findById(siteId).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, siteId));
    }

    /**
     * Records the addresses, which are not a secret: they are the operational fact that makes the
     * trail worth reading — who stopped being told, and when.
     */
    private static Map<String, Object> snapshot(SiteNotificationSettings settings) {
        return Map.of(
                "expiryEnabled", String.valueOf(settings.expiryEnabled()),
                "subscribers", settings.subscribers().stream().sorted().toList().toString());
    }
}
