package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;

import java.util.HashMap;
import java.util.Map;

/** Flattens a site into the before/after payload stored on an audit event. */
final class ProxySiteSnapshot {

    private ProxySiteSnapshot() {
    }

    static Map<String, Object> of(ProxySite site) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", site.id().toString());
        map.put("name", site.name());
        map.put("domain", site.domain().value());
        map.put("nginxInstanceId", site.nginxInstanceId().toString());
        map.put("adminState", site.adminState().name());
        map.put("status", site.status().name());
        map.put("activeFrom", String.valueOf(site.window().activeFrom()));
        map.put("expiresAt", String.valueOf(site.window().expiresAt()));
        map.put("sslEnabled", site.spec().sslEnabled());
        map.put("loadBalancingMethod", site.spec().loadBalancingMethod().name());
        map.put("upstreams", site.spec().upstreams().stream()
                .map(u -> u.scheme() + "://" + u.authority())
                .toList());
        map.put("upstreamCount", site.spec().upstreams().size());
        // Recorded so a change to them is visible after the fact, as an unnoticed reset once was not.
        map.put("locations", site.spec().locations().stream().map(ProxySiteSnapshot::describe).toList());
        // Who may sign in, never how: hashes stay out of the audit trail.
        map.put("protectionMode", site.spec().protection().mode().name());
        map.put("basicAuthUsers", site.spec().protection().basicUsers().stream()
                .map(net.xiidea.enginx.domain.proxy.BasicAuthUser::username)
                .toList());
        if (site.spec().protection().basicLoginPage() != null) {
            map.put("basicLoginPage", true);
            map.put("sessionTtlSeconds", site.spec().protection().basicLoginPage().sessionTtl().toSeconds());
        }
        net.xiidea.enginx.domain.proxy.EmailOtpPolicy emailOtp = site.spec().protection().emailOtp();
        if (emailOtp != null) {
            map.put("allowedEmails", emailOtp.allowedEmails().stream()
                    .map(net.xiidea.enginx.domain.proxy.AllowedEmail::value)
                    .toList());
            map.put("sessionTtlSeconds", emailOtp.sessionTtl().toSeconds());
        }
        net.xiidea.enginx.domain.proxy.OidcAccessPolicy oidc = site.spec().protection().oidc();
        if (oidc != null) {
            map.put("oidcAnyUser", oidc.anyUser());
            map.put("oidcUsers", oidc.users());
            map.put("oidcRoles", oidc.roles().stream().map(net.xiidea.enginx.domain.proxy.OidcRole::display).toList());
            map.put("sessionTtlSeconds", oidc.sessionTtl().toSeconds());
        }
        return Map.copyOf(map);
    }

    /**
     * A location as a line: its path, then what makes it differ from the site, so opening one to
     * the public or pointing it elsewhere reads as a change in the trail.
     */
    static String describe(net.xiidea.enginx.domain.proxy.LocationRule location) {
        StringBuilder line = new StringBuilder();
        if (location.matchType() == net.xiidea.enginx.domain.proxy.LocationMatchType.EXACT) {
            line.append("= ");
        }
        line.append(location.pathPattern());
        if (location.publicAccess()) {
            line.append(" [public]");
        }
        if (location.hasOwnUpstreams()) {
            line.append(" -> ").append(String.join(", ",
                    location.upstreams().stream().map(ProxySiteSnapshot::describe).toList()));
        }
        if (!location.settings().isEmpty()) {
            line.append(" [own proxy settings]");
        }
        return line.toString();
    }

    static String describe(UpstreamTarget upstream) {
        return upstream.scheme() + "://" + upstream.authority();
    }
}
