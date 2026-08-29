package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.UpstreamReachability;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Asks a site's host whether it can reach that site's upstreams.
 *
 * <p>Closes the second half of R3. A syntactically valid configuration can still point at a
 * service that is not there, and {@code nginx -t} will not say so — it checks grammar, not
 * intent. This turns "the deployment succeeded but the site 502s" into something answerable
 * before the deployment.
 *
 * <p>Deliberately its own endpoint rather than a step inside create and update. Two reasons, and
 * the second is the important one: a probe on the write path would add a network round trip to
 * every save, and R3 asks for a warning rather than a gate — a check that can block a save will
 * eventually block a legitimate one, at the moment an operator is trying to fix an outage.
 */
@Service
public class UpstreamCheckService {

    private final ProxySiteRepository sites;
    private final NginxInstanceRepository instances;
    private final NginxAgentPort agent;
    private final SitePermissionService permissions;

    public UpstreamCheckService(ProxySiteRepository sites, NginxInstanceRepository instances,
                                NginxAgentPort agent, SitePermissionService permissions) {
        this.sites = sites;
        this.instances = instances;
        this.agent = agent;
        this.permissions = permissions;
    }

    /**
     * @throws NotFoundException when the site does not exist, or the caller may not see it
     */
    @Transactional(readOnly = true)
    public List<UpstreamReachability> check(UUID siteId) {
        ProxySite site = sites.findById(siteId)
                .orElseThrow(() -> new NotFoundException("PROXY_SITE", siteId));

        // READ is the right bar. The result reveals only whether a host the caller can already
        // see can open a connection to an upstream that caller can already read from the site.
        permissions.requireSiteAccess(site, PermissionLevel.READ);

        NginxInstance instance = instances.findById(site.spec().nginxInstanceId())
                .orElseThrow(() -> new NotFoundException("NGINX_INSTANCE", site.spec().nginxInstanceId()));

        List<NginxAgentPort.UpstreamTarget> targets = site.spec().upstreams().stream()
                .map(UpstreamCheckService::toTarget)
                .toList();

        return agent.checkUpstreams(instance, targets);
    }

    private static NginxAgentPort.UpstreamTarget toTarget(UpstreamTarget upstream) {
        return new NginxAgentPort.UpstreamTarget(upstream.host(), upstream.port());
    }
}
