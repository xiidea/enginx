package net.xiidea.enginx.application.deployment;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.CertificateMaterialProvider;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.NginxConfigRenderer;
import net.xiidea.enginx.domain.deployment.RenderTarget;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shows what would be deployed.
 *
 * <p>Costs almost nothing because the renderer is already a pure function, and it is the single
 * highest-value thing for trusting the platform: an operator can see the exact directives their
 * change produces, and which files it would disturb, before any traffic is affected.
 */
@Service
public class ConfigurationPreviewService {

    private final ProxySiteRepository sites;
    private final ConfigBundleRepository bundles;
    private final NginxConfigRenderer renderer;
    private final CertificateMaterialProvider certificates;
    private final SitePermissionService permissions;
    private final NginxInstanceRepository instances;
    private final Clock clock;

    public ConfigurationPreviewService(ProxySiteRepository sites,
                                       ConfigBundleRepository bundles,
                                       NginxConfigRenderer renderer,
                                       CertificateMaterialProvider certificates,
                                       SitePermissionService permissions,
                                       NginxInstanceRepository instances,
                                       Clock clock) {
        this.instances = instances;
        this.sites = sites;
        this.bundles = bundles;
        this.renderer = renderer;
        this.certificates = certificates;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConfigurationPreview previewSite(UUID siteId) {
        ProxySite site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId));
        permissions.requireSiteAccess(site, PermissionLevel.READ);

        Instant now = clock.instant();
        // Rendered for the host it would be deployed to, so the preview shows the grammar and the
        // catch-all that host would actually get.
        RenderTarget target = instances.findById(site.nginxInstanceId())
                .map(RenderTarget::of)
                .orElse(RenderTarget.UNKNOWN);
        String siteConfiguration = renderer.renderSitePreview(site, certificates, target);

        List<ProxySite> deployable = sites.findDeployableForInstance(site.nginxInstanceId(), now);
        ConfigBundle proposed = renderer.render(UUID.randomUUID(), site.nginxInstanceId(), target, 0,
                deployable, certificates, "preview", now);
        ConfigBundle active = bundles.findActiveForInstance(site.nginxInstanceId()).orElse(null);

        return new ConfigurationPreview(
                siteConfiguration,
                active == null ? null : active.contentHash(),
                proposed.contentHash(),
                active == null || !active.hasSameContentAs(proposed),
                changedPaths(active, proposed));
    }

    /**
     * Which files differ. Compared by digest rather than by content, so the comparison never
     * needs to hold two full bundles of text in memory, and private keys are never compared as
     * strings.
     */
    private static List<String> changedPaths(ConfigBundle active, ConfigBundle proposed) {
        Map<String, String> before = digestsOf(active);
        Map<String, String> after = digestsOf(proposed);

        List<String> changed = new ArrayList<>();
        after.forEach((path, digest) -> {
            String previous = before.get(path);
            if (previous == null) {
                changed.add("added: " + path);
            } else if (!previous.equals(digest)) {
                changed.add("modified: " + path);
            }
        });
        before.keySet().stream()
                .filter(path -> !after.containsKey(path))
                .forEach(path -> changed.add("removed: " + path));

        changed.sort(String::compareTo);
        return changed;
    }

    private static Map<String, String> digestsOf(ConfigBundle bundle) {
        Map<String, String> digests = new LinkedHashMap<>();
        if (bundle != null) {
            for (BundleFile file : bundle.files()) {
                digests.put(file.path(), file.sha256());
            }
        }
        return digests;
    }
}
